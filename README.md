# ChannelReinforce

## Walle和加固宝的优雅结合

多渠道打包的方案，现在基本上分为Flavors的方案还有美团walle的方案。

一开始我个人选择的是的Flavors 的方案，在gradle中配置了flavors，但是很快发现弊端，打包很慢，尤其是执行assemble任务，六个渠道大概编译了十几分钟，这个是万万不能接受的。然后就开始采用美团的walle，这里先贴一下地址：

> 博客地址:https://tech.meituan.com/2017/01/13/android-apk-v2-signature-scheme.html
>
> Github:https://github.com/Meituan-Dianping/walle

美团的多渠道打包的原理也很容易理解:

> 瓦力通过在Apk中的`APK Signature Block`区块添加自定义的渠道信息来生成渠道包，从而提高了渠道包生成效率，

Walle的配置和使用也很简单，这里不做多介绍，详情请看官方文档。

在多渠道打包完之后，怎样进行加固还是个问题，我的加固方案采用的是360加固宝的加固方案，一个一个的加固，让我感觉太浪费时间，太麻烦，也属实有点low。

我最初的想法是通过一个task任务，来依赖walle 的assembleReleaseChannels 任务，然后遍历多渠道的输出路径来遍历多渠道apk，来进行遍历加固。然后通过实践发现，此方案也不可行，加固会报出 以下错误

> 上传失败10418提交次数过于频繁，请稍后再试

由于渠道信息是写在`APK Signature Block`区块中，apk的其他信息是相同的，加固也会报出重复提交相同文件的错误。

> 上传失败10419相同文件频繁提交，请稍后再试

除此之外，加固流程也是避免不了每个apk都上传，加固，下载，签名的过程。

除了加固的问题，还会面临一个跟严重的问题，加固重新签名之后，渠道信息就失效了。关于这个问题，在walle 的github上也说明了此问题:

Wiki:https://github.com/Meituan-Dianping/walle/wiki/360加固失效？

在Wiki中 也有大神给出了解决方案，但是需要Python环境，原谅我属实有点懒了，这样的流程让我感觉起来有点繁琐。

这不是我想要的最优解，于是我就另寻他法。

在使用Walle的过程中我发现 :

在assembleReleaseChannels 任务 执行前会先执行assembleRelease 任务，这就说明 assembleReleaseChannels 是依赖于assembleRelease  任务去执行的，assembleRelease 任务会在outputs目录下构建出release包，然后walle 在用这个apk 去写入多渠道，那么，我们是不是可以在这个原始的 release apk做下手脚，将 多渠道打包->加固 的流程进行 转变为 加固->多渠道打包。这样只需要对原始apk 加固一次，然后再进行写入渠道信息，这样就极大缩短了加固的时间，最重要的是，先加固，加固完成之后再写入渠道信息，这样渠道信息就不会进行覆盖了。



多渠道打包功能主要是由walle插件完成的，所以主要看下这部分过程。



walle项目源码中，library moudle 主要是开放给用户读取渠道信息使用的，具体实现是在payload_reader中，payload_writer moudle的功能就是写渠道信息了，walle-cli 是walle提供的命令行程序，plugin 就是多渠道打包的主要插件了。

在plugin中，groovy 目录下是 插件的主要实现源码。

GradlePlugin 是插件执行的入口， Extension 主要是Gradle 的配置信息，ChannelMaker 是主要task 实现。

方案的实现，主要是通过修改Extension 和  ChannelMaker 来实现。

先来看Extension 

```groovy
class Extension {
    static final String DEFAULT_APK_FILE_NAME_TEMPLATE = '${appName}-${buildType}-${channel}.apk'
    File apkOutputFolder
    String apkFileNameFormat
    File channelFile;
    File configFile;
    String variantConfigFileName;
    ......
}
```

这些字段看上去是不是很熟悉，没错，就是在我们在gradle 中 配置walle 的信息 

```groovy
walle {
    apkOutputFolder = new File("${project.buildDir}/outputs/channels")
    apkFileNameFormat = '${appName}-${packageName}-${channel}-${buildType}-v${versionName}-${versionCode}-     ${buildTime}-${flavorName}.apk'
    //configFile与channelFile两者必须存在一个，否则无法生成渠道包。两者都存在时优先执行configFile
    channelFile = new File("${project.getProjectDir()}/channel")
    //configFile = new File("${project.getProjectDir()}/config.json")
}
```

我们通过这里，就可以把加固宝的信息配置进去 

```groovy
class Extension {
    static final String DEFAULT_APK_FILE_NAME_TEMPLATE = '${appName}-${buildType}-${channel}.apk'

    File apkOutputFolder

    String apkFileNameFormat

    File channelFile;

    File configFile;

    String variantConfigFileName;
    //加固宝jar路径
    String jiaguPath
    //加固宝用户名
    String jiaguUser
    //加固宝 密码
    String jiaguPwd
}
```

然后对应的，我们就可以在gradle中进行对应的配置

```groovy
walle {
    // 指定渠道包的输出路径
    apkOutputFolder = new File("${project.buildDir}/outputs/channels");
    // 定制渠道包的APK的文件名称
    apkFileNameFormat = '${appName}-${channel}-${buildType}-v${versionName}-${buildTime}.apk';
    // 渠道配置文件
    channelFile = new File("${project.getProjectDir()}/channel")
  
    jiaguPath ="/Users/wyl/Downloads/360jiagubao_mac/jiagu/jiagu.jar"

    jiaguUser ="182xxxxx10"

    jiaguPwd  ="xixxxa1"
}
```

然后我们再看 ChannelMaker

这里主要看 packaging 方法。

```groovy
Extension extension = Extension.getConfig(targetProject);
```

这是拿到我们在gradle 配置中信息。

```groovy
def iterator = variant.outputs.iterator();
while (iterator.hasNext()) {
    def it = iterator.next();
    def apkFile = it.outputFile
    def apiIdentifier = null;
    if (!it.outputs[0].filters.isEmpty()) {
        def tempIterator = it.outputs[0].filters.iterator();
        while (tempIterator.hasNext()) {
            FilterData filterData = tempIterator.next();
            if (filterData.filterType == "ABI") {
                apiIdentifier = filterData.identifier
                break;
            }
        }
    }
    if (apkFile == null || !apkFile.exists()) {
        throw new GradleException("${apkFile} is not existed!");
    }
}
```

这里就是遍历构建后的apk，然后对apk进行判空验证。

其实我们了解到这里，就已经够了，因为后续的多渠道，写入渠道号都是基于这个apk来进行操作的，我们只要在这里加上加固的流程就可以满足我们的需求了。



首先创建一个文件夹，来存放我们加固后的apk文件

```groovy
def appFilePath = project.getProjectDir().absolutePath + "/build/outputs/jiagu"
File appDoc = new File(appFilePath)
if (!appDoc.exists()) appDoc.mkdir()
```

获取签名的方法在GrdlePlugin中 已经给到了，可以直接拿来用 

```groovy
SigningConfig getSigningConfig(BaseVariant variant) {
    return variant.buildType.signingConfig == null ? variant.mergedFlavor.signingConfig :     variant.buildType.signingConfig;
}
```

然后就可以进行加固操作了

```groovy
if (extension.jiaguPath != null && extension.jiaguUser != null && extension.jiaguPwd != null) {
    project.exec {
        it.commandLine("java", "-jar", extension.jiaguPath, "-login", extension.jiaguUser, extension.jiaguPwd)
    }

    project.exec {
        it.commandLine("java", "-jar", extension.jiaguPath, "-importsign", signingConfig.storeFile, signingConfig.storePassword, signingConfig.keyAlias, signingConfig.keyPassword)
    }

    def iterator = variant.outputs.iterator();
    while (iterator.hasNext()) {
        def it = iterator.next();
        def apkFile = it.outputFile
        project.exec {
            it.commandLine("java", "-jar", extension.jiaguPath, "-jiagu", apkFile, appFilePath, "-autosign")
        }
    }
}
```

然后通过遍历加固文件夹的apk文件，就可以对我们加固后的apk进行多渠道了。

```groovy
File[] files = appDoc.listFiles()
for (i in 0..<files.size()) {
    File apkFile = files[i]

    if (apkFile == null || !apkFile.exists()) {
        throw new GradleException("${apkFile} is not existed!");
    }

    checkV2Signature(apkFile)
    .......
}
```

后续的流程和原流程一样就可以了。

我是通过maven插件 将插件发布在了本地 进行测试

在插件目录的build.gradle中依赖maven插件

```groovy
apply plugin: 'maven'
```

然后配置发布信息:

```groovy
group = 'com.xl.channel-plugin'
version = "1.0.0"

//打包上传到本地
uploadArchives {
    repositories {
        flatDir {
            dirs '../repo/'
        }
    }
}
```
通过/upload/uploadArchives task 来发布插件

在根目录的gradle中 引入仓库地址和插件

```groovy
repositories {
    google()
    mavenCentral()
    flatDir {
        dirs './repo/'
    }
}

classpath 'com.xl.channel-plugin:channel_plugin:1.0.0'

//app 的build.gradle 中
apply plugin: 'xl-channel'
```

然后对walle进行配置

```groovy
walle {
    // 指定渠道包的输出路径
    apkOutputFolder = new File("${project.buildDir}/outputs/channels");
    // 定制渠道包的APK的文件名称
    apkFileNameFormat = '${appName}-${channel}-${buildType}-v${versionName}-${buildTime}.apk';
    // 渠道配置文件
    channelFile = new File("${project.getProjectDir()}/channel")

    jiaguPath ="/Users/wyl/Downloads/360jiagubao_mac/jiagu/jiagu.jar"

    jiaguUser ="182xxlxlxl10"

    jiaguPwd  ="xixixixi"
}
```


然后执行assembleReleaseChannels  task 来测试一下我们的成果

最后在build/outputs 目录下，会生成一个两个文件夹，jiagu 和 channles 。jiagu存放的加固后的apk，channels存放的是多渠道的apk。

至此，这个方案站在巨人的肩膀上就完成了，感谢美团walle。

有问题欢迎留言讨论。

![WechatIMG237](https://user-images.githubusercontent.com/33646116/147386458-399efc28-61b2-4c02-9213-62a21173310b.jpeg)

