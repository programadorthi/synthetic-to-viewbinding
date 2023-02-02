package dev.programadorthi.migration.visitor

import android.databinding.tool.ext.parseXmlResourceReference
import android.databinding.tool.ext.toCamelCaseAsVar
import com.intellij.psi.PsiElement
import com.intellij.psi.util.elementType
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlElementType
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import dev.programadorthi.migration.helper.AndroidView
import org.jetbrains.kotlin.idea.references.SyntheticPropertyAccessorReference
import org.jetbrains.kotlin.idea.structuralsearch.visitor.KotlinRecursiveElementVisitor
import org.jetbrains.kotlin.nj2k.postProcessing.resolve
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtValueArgument

class SyntheticReferenceVisitor : KotlinRecursiveElementVisitor() {
    private val mutableSyntheticReferences = mutableListOf<AndroidView>()
    val syntheticReferences: List<AndroidView>
        get() = mutableSyntheticReferences

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        tryFindReferenceInsideLambdaArguments(expression.valueArguments)
        for (reference in expression.references) {
            tryFindReferenceUpToBottomRecursively(reference.element, 0)
        }
        for (element in expression.children) {
            tryFindReferenceUpToBottomRecursively(element, 0)
        }
    }

    override fun visitReferenceExpression(expression: KtReferenceExpression) {
        super.visitReferenceExpression(expression)
        for (reference in expression.references) {
            if (reference is SyntheticPropertyAccessorReference) {
                val resolvedReferences = reference.element.references.mapNotNull { it.resolve() }
                for (resolved in resolvedReferences) {
                    tryMapXmlAttributeValue(resolved)
                }
            }
        }
    }

    /**
     * Some View Binding can be referenced inside lambda parameters to other calls expression like below:
     *
     * xmlAndroidId.something(
     *     someLambdaOperation = {
     *         xmlAndroidId.doSomething()
     *     }
     * )
     */
    private fun tryFindReferenceInsideLambdaArguments(arguments: List<KtValueArgument>) {
        for (argument in arguments) {
            val lambdaExpressions = argument.children
                .filterIsInstance<KtLambdaExpression>()
                .mapNotNull { it.functionLiteral.bodyBlockExpression?.children?.toList() }
                .flatten()
            for (element in lambdaExpressions) {
                tryFindReferenceUpToBottomRecursively(element, 0)
            }
        }
    }

    /**
     * You can think depth as using lambda inside lambda as below.
     * Unfortunately, some guys like lambda inside lambda :/
     *
     * myFlow.collect {
     *      depth 1
     *      refOne.lambda {
     *          depth 2
     *          refOne.lambda {
     *              depth 3
     *              refOne.lambda {
     *                  depth 4
     *                  refOne.lambda {
     *                      depth 5
     *                  }
     *              }
     *          }
     *      }
     * }
     */
    private fun tryFindReferenceUpToBottomRecursively(element: PsiElement, depth: Int) {
        if (depth >= 5) return
        for (child in element.children) {
            if (child is KtReferenceExpression) {
                tryMapXmlAttributeValue(child.resolve())
            }
            if (child is KtValueArgument) {
                tryFindReferenceInsideLambdaArguments(listOf(child))
            }
            if (child is KtCallExpression) {
                tryFindReferenceInsideLambdaArguments(child.valueArguments)
            }
            tryFindReferenceUpToBottomRecursively(child, depth + 1)
        }
    }

    private fun tryMapXmlAttributeValue(psiElement: PsiElement?) {
        if (psiElement == null || psiElement.elementType != XmlElementType.XML_ATTRIBUTE_VALUE) return

        val xmlAttributeValue = psiElement as XmlAttributeValue
        if (!xmlAttributeValue.value.startsWith(ANDROID_VIEW_ID_PREFIX)) return

        val file = xmlAttributeValue.containingFile as? XmlFile ?: return
        mutableSyntheticReferences += AndroidView(
            isIncludeTag = checkForSyntheticIncludeTag(xmlAttributeValue),
            layoutNameWithoutExtension = file.name.removeSuffix(".xml"),
            rootTagName = file.rootTag?.name ?: "",
            viewId = xmlAttributeValue.text
                .removeSurrounding("\"")
                .parseXmlResourceReference()
                .name
                .toCamelCaseAsVar()
        )
    }

    /**
     * current ref is: "@+id/someIdentifier"
     * His parent is:   android:id="@+id/someIdentifier"
     * His parent from parent is always a tag: <Tag android:id="@+id/someIdentifier" />
     *
     * If parent tag is <include /> we need to add .root to binding name
     */
    private fun checkForSyntheticIncludeTag(xmlAttributeValue: XmlAttributeValue): Boolean {
        var currentParent = xmlAttributeValue.parent
        while (true) {
            if (currentParent == null || currentParent is XmlTag) break
            currentParent = currentParent.parent
        }
        return currentParent is XmlTag && currentParent.name == "include"
    }

    private companion object {
        private const val ANDROID_VIEW_ID_PREFIX = "@+id/"
    }
}