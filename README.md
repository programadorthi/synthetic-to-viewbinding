# synthetic-to-viewbinding
A Intellij plugin to migrate Kotlin synthetics to Jetpack view binding

> Please, this plugin is not perfect. Read all sections below before use it.
> 
> It was developed using my context that can be totally different from your. Keep it in mind!

#### This plugin supports kotlin source and build.gradle(.kts) files only.

## How to use
1. Install the plugin from Marketplace (link soon)
2. On the android module, enable viewBinding in your build.gradle(.kts)
```groovy
android {
    // New AGP version setup
    viewBinding.enable = true
    // Old AGP version setup
    buildFeatures {
        viewBinding true
    }
}
```
3. Right click on your class or module and select the menu: `Refactor -> Migrate Synthetic to ViewBinding`
4. In the popup select your options, filters and click in the button `Run`.

> Popup options are from your IDE code styles settings and run after view binding migration. 

## Features

- Activity, Dialog, ViewGroup and View migration
- Replace setContentView(R.layout.name) with setContentView(binding.root)
- Remove View.inflate() or LayoutInflate.inflate from init {} blocks
- Support for multiple synthetics in the same class
- Remove plugin and android extensions configurations from build(.gradle|.kts)
- Update @Parcelize imports and add plugin to build(.gradle|.kts)
- Generate bind behaviors to ViewStub inflate()
- Organize imports, Reformat code, Code cleanup based on your IDE code style settings

> At the end, all synthetics references become lazy {} properties because replacing all references with `binding.` prefix is a mess and is easy for pull request reviewers.

From:
```kotlin
class MyClass : AnySupportedType {
    fun something() {
        synthetic1.do()
        synthetic2.do()
        ...
    }
}
```
To:
```kotlin
class MyClass : AnySupportedType {
    // For Activity, Dialog, ViewGroup or View without parent
    // Inside MyClass is the same as: MyViewBinding.inflate(layoutInflater)
    private val bindingName by viewBinding(MyViewBinding::inflate)
    // For ViewGroup or View used as xml root tag and it is inflated already
    // Like: my_layout.xml
    // <com.example.MyClass> ... </com.example.MyClass>
    // Inside MyClass is the same as: MyLayoutBinding.bind(this)
    private val bindingName by viewBinding(MyLayoutBinding::bind)
    // For ViewGroup or View not used as xml root tag and will be the parent
    // Like: my_layout.xml
    // <SomeLayout> ... </SomeLayout>
    // Inside MyClass is the same as: MyLayoutBinding.inflate(layoutInflater, this, true) 
    private val bindingName by viewBindingAsChild(MyLayoutBinding::inflate)
    // For ViewGroup or View having <merge> as xml root tag.
    // Like: my_layout.xml
    // <merge> ... </merge>
    // Inside MyClass is the same as: MyLayoutBinding.inflate(layoutInflater, this) 
    private val bindingName by viewBindingMergeTag(MyLayoutBinding::inflate)

    private val synthetic1 by lazy { bindingName.synthetic1 }
    private val synthetic2 by lazy { bindingName.synthetic2 }
    
    // for <include/> layout
    // include layout binding are, almost, already resolved by view binding plugin
    // so we do not need do manual bind like: MyIncludeLayout.bind(bindingName.root)
    private val includeId by lazy { bindingName.includeId }
    
    // for ViewStub
    private val viewStubId by lazy { 
        val view = bindingName.viewStubId.inflate()
        ViewStubBinding.bind(view)
    }
    
    fun something() {
        synthetic1.do()
        synthetic2.do()
        includeId.do()
        viewStubId.something()
    }
}
```

Things to know here:
1. `viewBinding` came from our custom extension functions and all rights to [@Zhuinden](https://github.com/Zhuinden/simple-stack) article [Simple one-liner ViewBinding in Fragments and Activities with Kotlin](https://medium.com/@Zhuinden/simple-one-liner-viewbinding-in-fragments-and-activities-with-kotlin-961430c6c07c)
2. If you do not have this kind of extensions, you are free to adapt the plugin. Maybe I do in the future. Pull requests are welcome!

## Groupie features

- Replace super type from Item to BindableItem<MyViewBinding>

From:
```kotlin
class MyViewHolder : Item() {
    ...
}
```
To:
```kotlin
class MyViewHolder : BindableItem<MyViewBinding>() {
    ...
}
```

- Add initializeViewBinding(view) function

```kotlin
class MyViewHolder : BindableItem<MyViewBinding>() {
    override fun initializeViewBinding(view: View): MyViewBinding =
        MyViewBinding.bind(view)
}
``` 

- Replace `itemView` or `contentView` with `root`
- Replace `GroupieViewHolder` functions parameters with MyViewBinding class

From:
```kotlin
class MyViewHolder : Item() {
    override fun bind(viewHolder: GroupieViewHolder, position: Int) {
        ...
    }
}
``` 
To:
```kotlin
class MyViewHolder : BindableItem<MyViewBinding>() {
    override fun bind(viewHolder: MyViewBinding, position: Int) {
        ...
    }
}
``` 

# Things not supported to keep in mind

- Fragments because we do not use it anywhere! :D
- RecyclerView.Adapter because we use Groupie instead.
- Inner classes
- Constructor layout Activity|Fragment(R.layout.something)
- Functions having `GroupieViewHolder` as argument type but there is no synthetic references in your body are not changed by default.
```kotlin
class MyViewHolder : Item() {
    // bind parameter type will not be replaced because it is difficult to know the view binding type
    override fun bind(viewHolder: GroupieViewHolder, position: Int) {
        // No synthetic references here or used in another function like
        functionHavingSyntheticReferences(viewHolder)
    }
}
``` 
- Functions having multiples GroupieViewHolder because I do not know whom is the source of truth to synthetics inside it.
```kotlin
class MyViewHolder : Item() {
    // Two references to GroupieViewHolder to know the source of truth to synthetics :/
    fun GroupieViewHolder.doSomenthing(other: GroupieViewHolder) {
        // synthetic references here
    }
}
``` 
- Referencing root view with itemView at same time because itemView is root already

From:
```kotlin
class MyViewHolder : Item() {
    fun doSomenthing(viewHolder: GroupieViewHolder) {
        // here itemView and rootTagId are the same root tag
        viewHolder.itemView.rootTagId.something()
    }
}
``` 
To:
```kotlin
class MyViewHolder : BindableItem<MyViewBinding>() {
    fun doSomenthing(viewHolder: MyViewBinding) {
        // will change to root but it is an error
        viewHolder.root.rootTagId.something()
    }
}
``` 
- ViewStub resolution property when declared by lazy { viewStub.inflate() } or referenced in <include/>. As you saw above, properties for ViewStub are automatically generated. So, if you have custom inflation, you need to fix
- <include/> layouts binding not resolved by view binding plugin. As you saw above, includes are almost resolve by view binding plugin. But, sometimes, you will need to do manual binding.

## Author
- Thiago Santos - [@programadorthi](https://github.com/programadorthi)