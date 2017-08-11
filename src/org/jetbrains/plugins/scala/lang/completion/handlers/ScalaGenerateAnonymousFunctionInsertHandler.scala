package org.jetbrains.plugins.scala.lang.completion.handlers

import com.intellij.codeInsight.CodeInsightUtilCore
import com.intellij.codeInsight.completion.{InsertHandler, InsertionContext}
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.template._
import com.intellij.codeInsight.template.impl.ConstantNode
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.extensions.inWriteAction
import org.jetbrains.plugins.scala.lang.completion.ScalaCompletionUtil
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil
import org.jetbrains.plugins.scala.lang.psi.api.ScalaRecursiveElementVisitor
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.{ScCaseClause, ScTypedPattern}
import org.jetbrains.plugins.scala.lang.psi.api.base.types._
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScFunctionExpr
import org.jetbrains.plugins.scala.lang.psi.types.api.ScTypePresentation
import org.jetbrains.plugins.scala.lang.psi.types.{ScAbstractType, ScType}

import scala.collection.mutable

/**
  * @author Alexander Podkhalyuzin
  */

class ScalaGenerateAnonymousFunctionInsertHandler(params: Seq[ScType], braceArgs: Boolean) extends InsertHandler[LookupElement] {
  def handleInsert(context: InsertionContext, item: LookupElement) {
    def collectAbstracts(`type`: ScType): Seq[ScAbstractType] = {
      val set: mutable.HashSet[ScAbstractType] = new mutable.HashSet[ScAbstractType]

      `type`.visitRecursively {
        case a: ScAbstractType => set += a
        case _ =>
      }

      set.toSeq
    }

    val abstracts = new mutable.HashSet[ScAbstractType]
    for (param <- params) abstracts ++= collectAbstracts(param)

    val editor = context.getEditor
    val document = editor.getDocument
    context.setAddCompletionChar(false)
    val text = ScalaCompletionUtil.generateAnonymousFunctionText(braceArgs, params, canonical = true,
      arrowText = ScalaPsiUtil.functionArrow(editor.getProject))
    document.insertString(editor.getCaretModel.getOffset, text)
    val documentManager = PsiDocumentManager.getInstance(context.getProject)
    documentManager.commitDocument(document)
    val file = documentManager.getPsiFile(document)
    val startOffset = context.getStartOffset
    val endOffset = startOffset + text.length()
    val commonParent = PsiTreeUtil.findCommonParent(file.findElementAt(startOffset),
      file.findElementAt(endOffset - 1))
    if (commonParent.getTextRange.getStartOffset != startOffset ||
      commonParent.getTextRange.getEndOffset != endOffset) {
      document.insertString(endOffset, " ")
      editor.getCaretModel.moveToOffset(endOffset + 1)
      return
    }

    ScalaPsiUtil.adjustTypes(commonParent)

    val builder: TemplateBuilderImpl = TemplateBuilderFactory.getInstance().
      createTemplateBuilder(commonParent).asInstanceOf[TemplateBuilderImpl]

    val abstractNames = abstracts.map(at => at.parameterType.name + ScTypePresentation.ABSTRACT_TYPE_POSTFIX)


    def seekAbstracts(te: ScTypeElement) {
      val visitor = new ScalaRecursiveElementVisitor {
        override def visitSimpleTypeElement(simple: ScSimpleTypeElement) {
          simple.reference match {
            case Some(ref) =>
              val refName = ref.refName
              if (abstractNames.contains(refName)) {
                val postfixLength = ScTypePresentation.ABSTRACT_TYPE_POSTFIX.length
                val node = abstracts.find(a => a.parameterType.name + ScTypePresentation.ABSTRACT_TYPE_POSTFIX == refName) match {
                  case Some(abstr) =>
                    abstr.simplifyType match {
                      case t if t.isAny || t.isNothing =>
                        new ConstantNode(refName.substring(0, refName.length - postfixLength))
                      case tp =>
                        new ConstantNode(tp.presentableText)
                    }
                  case None =>
                    new ConstantNode(refName.substring(0, refName.length - postfixLength))
                }
                builder.replaceElement(simple, refName, node, false)
              }
            case None =>
          }
        }
      }
      te.accept(visitor)
    }

    commonParent match {
      case f: ScFunctionExpr =>
        for (parameter <- f.parameters) {
          parameter.typeElement match {
            case Some(te) =>
              seekAbstracts(te)
            case _ =>
          }
          builder.replaceElement(parameter.nameId, parameter.name)
        }
      case c: ScCaseClause => c.pattern match {
        case Some(pattern) =>
          for (binding <- pattern.bindings) {
            binding match {
              case tp: ScTypedPattern => tp.typePattern match {
                case Some(tpe) =>
                  seekAbstracts(tpe.typeElement)
                case _ =>
              }
              case _ =>
            }
            builder.replaceElement(binding.nameId, binding.name)
          }
        case _ =>
      }
    }

    CodeInsightUtilCore.forcePsiPostprocessAndRestoreElement(commonParent)

    val template = builder.buildTemplate()
    for (name <- abstractNames) {
      val actualName: String = name.substring(0, name.length - ScTypePresentation.ABSTRACT_TYPE_POSTFIX.length)
      template.addVariable(name, actualName, actualName, false)
    }

    document.deleteString(commonParent.getTextRange.getStartOffset, commonParent.getTextRange.getEndOffset)
    TemplateManager.getInstance(context.getProject).startTemplate(editor, template, new TemplateEditingAdapter {
      override def templateFinished(template: Template, brokenOff: Boolean) {
        if (!brokenOff) {
          val offset = editor.getCaretModel.getOffset
          inWriteAction {
            document.insertString(offset, " ")
          }
          editor.getCaretModel.moveToOffset(offset + 1)
        }
      }
    })
  }
}