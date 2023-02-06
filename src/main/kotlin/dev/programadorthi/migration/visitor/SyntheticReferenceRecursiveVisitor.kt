package dev.programadorthi.migration.visitor

import android.databinding.tool.ext.parseXmlResourceReference
import android.databinding.tool.ext.toCamelCaseAsVar
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.util.elementType
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlElementType
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import dev.programadorthi.migration.helper.AndroidView
import org.jetbrains.kotlin.asJava.namedUnwrappedElement
import org.jetbrains.kotlin.nj2k.postProcessing.resolve
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBinaryExpressionWithTypeRHS
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassInitializer
import org.jetbrains.kotlin.psi.KtContainerNode
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtPropertyDelegate
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtTryExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.psi.KtVisitorVoid
import org.jetbrains.kotlin.psi.KtWhenEntry
import org.jetbrains.kotlin.psi.KtWhenExpression

class SyntheticReferenceRecursiveVisitor : KtVisitorVoid() {
    private val mutableAndroidViews = mutableListOf<AndroidView>()
    private val mutableViewHolderItemViews = mutableListOf<PsiReference>()
    val androidViews: List<AndroidView>
        get() = mutableAndroidViews

    val viewHolderItemViews: List<PsiReference>
        get() = mutableViewHolderItemViews

    // ========================================================================
    // Start section that do something
    // ========================================================================

    /**
     * Class in the file
     */
    override fun visitClass(klass: KtClass) {
        super.visitClass(klass)
        val body = klass.body ?: return
        body.accept(this)
    }

    /**
     * Reference to other element
     */
    override fun visitReferenceExpression(expression: KtReferenceExpression) {
        super.visitReferenceExpression(expression)
        tryMapXmlAttributeValue(expression.resolve(), expression.references.firstOrNull())
    }

    private fun tryMapXmlAttributeValue(psiElement: PsiElement?, psiReference: PsiReference?) {
        if (psiElement == null || psiReference == null) return
        if (checkAndMapGroupieValue(psiElement, psiReference)) return
        if (psiElement.elementType != XmlElementType.XML_ATTRIBUTE_VALUE) return

        val xmlAttributeValue = psiElement as XmlAttributeValue
        if (!xmlAttributeValue.value.startsWith(ANDROID_VIEW_ID_PREFIX)) return

        val file = xmlAttributeValue.containingFile as? XmlFile ?: return
        mutableAndroidViews += AndroidView(
            reference = psiReference,
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

    private fun checkAndMapGroupieValue(psiElement: PsiElement, psiReference: PsiReference): Boolean {
        val currentPsiElement = psiElement.namedUnwrappedElement ?: return false
        val parentName = currentPsiElement.parent?.namedUnwrappedElement?.name ?: ""
        if (("itemView" == currentPsiElement.name || "containerView" == currentPsiElement.name) &&
            ("ViewHolder" == parentName || "GroupieViewHolder" == parentName)
        ) {
            mutableViewHolderItemViews += psiReference
            return true
        }
        return false
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

    // ========================================================================
    // End section that do something
    // ========================================================================

    // ========================================================================
    // Start recursive operations section
    // ========================================================================

    /**
     * Function argument
     *
     * doSomething(something)  <-- something is an argument
     * setOnClickListener {}   <-- lambda is an argument for setOnClickListener function
     * something.apply {}      <-- {} is an argument for apply function
     */
    override fun visitArgument(argument: KtValueArgument) {
        super.visitArgument(argument)
        for (child in argument.children) {
            child.accept(this)
        }
    }

    /**
     * Assignment operation (=)
     */
    override fun visitBinaryExpression(expression: KtBinaryExpression) {
        super.visitBinaryExpression(expression)
        for (child in expression.children) {
            child.accept(this)
        }
    }

    /**
     * Instance of operation (is)
     */
    override fun visitBinaryWithTypeRHSExpression(expression: KtBinaryExpressionWithTypeRHS) {
        super.visitBinaryWithTypeRHSExpression(expression)
        for (child in expression.children) {
            child.accept(this)
        }
    }

    /**
     * Function block {}
     */
    override fun visitBlockExpression(expression: KtBlockExpression) {
        super.visitBlockExpression(expression)
        for (child in expression.children) {
            child.accept(this)
        }
    }

    /**
     * Function call
     *
     * lazy {}
     * doSomething()
     * doOtherSomething(something)
     * setOnClickListener {}
     * super.onStart()       <-- here call expression is onStart() only
     * something.apply {}    <-- here call expression is apply {} only
     */
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        for (child in expression.children) {
            child.accept(this)
        }
    }

    /**
     * try {} catch {}
     */
    override fun visitCatchSection(catchClause: KtCatchClause) {
        super.visitCatchSection(catchClause)
        for (child in catchClause.children) {
            child.accept(this)
        }
    }

    /**
     * Class content inside {}
     */
    override fun visitClassBody(classBody: KtClassBody) {
        super.visitClassBody(classBody)
        for (child in classBody.children) {
            child.accept(this)
        }
    }

    /**
     * init {}
     */
    override fun visitClassInitializer(initializer: KtClassInitializer) {
        super.visitClassInitializer(initializer)
        for (child in initializer.children) {
            child.accept(this)
        }
    }

    /**
     * Expression using Dot(.)
     *
     * super.onStart()
     * variable.run()
     * noNullable.doSomething()
     */
    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        for (child in expression.children) {
            child.accept(this)
        }
    }

    /**
     *  do {} while()
     *
     *  Check [visitKtElement] to know how access his condition or body content
     */
    override fun visitDoWhileExpression(expression: KtDoWhileExpression) {
        super.visitDoWhileExpression(expression)
        for (child in expression.children) {
            child.accept(this)
        }
    }

    /**
     * for () {}
     *
     * Check [visitKtElement] to know how access his condition or body content
     */
    override fun visitForExpression(expression: KtForExpression) {
        super.visitForExpression(expression)
        for (child in expression.children) {
            child.accept(this)
        }
    }

    /**
     * if {} or both if {} else {}
     *
     * Check [visitKtElement] to know how access his condition or body content
     */
    override fun visitIfExpression(expression: KtIfExpression) {
        super.visitIfExpression(expression)
        for (child in expression.children) {
            child.accept(this)
        }
    }

    /**
     * Any element
     */
    override fun visitKtElement(element: KtElement) {
        super.visitKtElement(element)
        // if/else/while/for conditional section () or content body {}
        if (element is KtContainerNode) {
            for (child in element.children) {
                child.accept(this)
            }
        }
    }

    /**
     * Kotlin file content
     */
    override fun visitKtFile(file: KtFile) {
        super.visitKtFile(file)
        for (child in file.children) {
            if (child is KtClass || child is KtNamedFunction) {
                child.accept(this)
            }
        }
    }

    /**
     * Function declared in class body or top-level file
     *
     * class {
     *     fun ...   <- this is a named function
     * }
     *
     * fun ...   <- this is a named function too
     */
    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        for (child in function.children) {
            child.accept(this)
        }
    }

    /**
     * Any argument with lambda value
     *
     * myVariable = {}         <-- {} is a lambda expression
     * setOnClickListener {}   <-- {} is a lambda expression
     * something.apply {}      <-- {} is a lambda expression
     * something.map {}        <-- {} is a lambda expression
     */
    override fun visitLambdaExpression(lambdaExpression: KtLambdaExpression) {
        super.visitLambdaExpression(lambdaExpression)
        for (child in lambdaExpression.functionLiteral.children) {
            child.accept(this)
        }
    }

    /**
     * Property inside class, function or any declaration local
     * Almost declared using val or var
     */
    override fun visitProperty(property: KtProperty) {
        super.visitProperty(property)
        for (child in property.children) {
            child.accept(this)
        }
    }

    /**
     * get() or set(...) for a property
     */
    override fun visitPropertyAccessor(accessor: KtPropertyAccessor) {
        super.visitPropertyAccessor(accessor)
        for (child in accessor.children) {
            child.accept(this)
        }
    }

    /**
     * Delegated property
     *
     * val variable by lazy {}  <-- by is a property delegate
     */
    override fun visitPropertyDelegate(delegate: KtPropertyDelegate) {
        super.visitPropertyDelegate(delegate)
        for (child in delegate.children) {
            child.accept(this)
        }
    }

    /**
     * Expression using ?.
     *
     * nullable?.something()
     * fun1()?.fun2()
     */
    override fun visitSafeQualifiedExpression(expression: KtSafeQualifiedExpression) {
        super.visitSafeQualifiedExpression(expression)
        for (child in expression.children) {
            child.accept(this)
        }
    }

    /**
     * String in template form "${}" or """${}"""
     */
    override fun visitStringTemplateExpression(expression: KtStringTemplateExpression) {
        super.visitStringTemplateExpression(expression)
        for (child in expression.children) {
            child.accept(this)
        }
    }

    /**
     * String template entry "${entry1}..." or """${entry1}something${entry2}..."""
     */
    override fun visitStringTemplateEntry(entry: KtStringTemplateEntry) {
        super.visitStringTemplateEntry(entry)
        for (child in entry.children) {
            child.accept(this)
        }
    }

    /**
     * try {} catch {}
     */
    override fun visitTryExpression(expression: KtTryExpression) {
        super.visitTryExpression(expression)
        for (child in expression.children) {
            child.accept(this)
        }
    }

    /**
     * Argument list for a function call
     *
     * fun1(arg1, arg2, ...)
     * something.map { arg1, arg2, ... }
     */
    override fun visitValueArgumentList(list: KtValueArgumentList) {
        super.visitValueArgumentList(list)
        for (argument in list.arguments) {
            argument.accept(this)
        }
    }

    /**
     * when (...) {} or when {}
     */
    override fun visitWhenExpression(expression: KtWhenExpression) {
        super.visitWhenExpression(expression)
        for (child in expression.children) {
            child.accept(this)
        }
    }

    /**
     * when ... {
     *     entry1 -> ...
     *     entry2 -> ...
     *     ...
     *     else -> ...     <-- else is a when entry too
     * }
     */
    override fun visitWhenEntry(jetWhenEntry: KtWhenEntry) {
        super.visitWhenEntry(jetWhenEntry)
        for (child in jetWhenEntry.children) {
            child.accept(this)
        }
    }

    // ========================================================================
    // End recursive operations section
    // ========================================================================

    private companion object {
        private const val ANDROID_VIEW_ID_PREFIX = "@+id/"
    }
}