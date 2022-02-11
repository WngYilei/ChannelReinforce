package com.xl.channel_plugin


import com.android.build.gradle.api.BaseVariant
import com.android.builder.model.SigningConfig
import com.google.common.base.Charsets
import com.google.common.hash.HashCode
import com.google.common.hash.HashFunction
import com.google.common.hash.Hashing
import com.google.common.io.Files
import com.google.gson.Gson
import com.xl.channel_plugin.android.apksigner.core.ApkVerifier
import com.xl.channel_plugin.android.apksigner.core.internal.util.ByteBufferDataSource
import com.xl.channel_plugin.android.apksigner.core.util.DataSource
import com.xl.channel_plugin.writer.ChannelWriter
import groovy.text.SimpleTemplateEngine
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.TaskAction

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat

class ChannelMaker extends DefaultTask {

    private static final String DOT_APK = ".apk";

    public BaseVariant variant;

    public Project targetProject;

    public void setup() {
        description "Make Multi-Channel"
        group "Package"
    }

    private static final String PROPERTY_CHANNEL_LIST = 'channelList'
    private static final String PROPERTY_CHANNEL_FILE = 'channelFile'
    private static final String PROPERTY_CONFIG_FILE = 'configFile'
    private static final String PROPERTY_EXTRA_INFO = 'extraInfo'


    @TaskAction
    public void packaging() {
        long startTime = System.currentTimeMillis()
        Extension extension = Extension.getConfig(targetProject);

        def appFilePath = project.getProjectDir().absolutePath + "/build/outputs/jiagu"
        if (extension.isFlutter) {
            String path = project.getProjectDir().parent.split("android")[0]
            appFilePath = path + "/build/app/outputs/jiagu"
        }

        File appDoc = new File(appFilePath)
        if (!appDoc.exists()) appDoc.mkdir()

        SigningConfig signingConfig = getSigningConfig(variant)
        if (extension.jiaguPath != null && extension.jiaguUser != null && extension.jiaguPwd != null) {
            project.exec {
                it.commandLine("java", "-jar", extension.jiaguPath, "-login", extension.jiaguUser, extension.jiaguPwd)
            }

            project.exec {
                it.commandLine("java", "-jar", extension.jiaguPath, "-importsign", signingConfig.storeFile, signingConfig.storePassword, signingConfig.keyAlias, signingConfig.keyPassword)
            }

            if (extension.isFlutter) {
                String path = project.getProjectDir().parent.split("android")[0]
                String apkPath = path + "build/app/outputs/apk/${variant.name.toLowerCase()}"
                File[] files = new File(apkPath).listFiles()
                if (files == null || files.size() == 0) return

                files.each {
                    if (it.name.endsWith(".apk")) {
                        File oldApk = new File(appFilePath+"/oldApk.apk")
                        com.android.utils.FileUtils.copyFile(it,oldApk)
                        project.exec {
                            it.commandLine("java", "-jar", extension.jiaguPath, "-jiagu", oldApk, appFilePath, "-autosign")
                        }
                        oldApk.delete()
                    }
                }
            } else {
                def iterator = variant.outputs.iterator();
                while (iterator.hasNext()) {
                    def it = iterator.next();
                    def apkFile = it.outputFile
                    project.exec {
                        it.commandLine("java", "-jar", extension.jiaguPath, "-jiagu", apkFile, appFilePath, "-autosign")
                    }
                }
            }

        }

        File[] files = appDoc.listFiles()
        for (i in 0..<files.size()) {
            File apkFile = files[i]

            if (apkFile == null || !apkFile.exists()) {
                throw new GradleException("${apkFile} is not existed!");
            }

            checkV2Signature(apkFile)

            File channelOutputFolder = apkFile.parentFile;
            if (extension.apkOutputFolder instanceof File) {
                channelOutputFolder = extension.apkOutputFolder;
                if (!channelOutputFolder.parentFile.exists()) {
                    channelOutputFolder.parentFile.mkdirs();
                }
            }

            String abi = "abi"
            if (apkFile.name.contains("armeabi-v7a") || apkFile.name.contains("arm64-v8a")) {
                String[] abiNames = apkFile.name.split("-")
                if (abiNames != null && abiNames.length > 0)
                    abi = abiNames[1] + abiNames[2]
            }

            def nameVariantMap = [
                    'appName'    : targetProject.name,
                    'abiName'    : abi,
                    'projectName': targetProject.rootProject.name,
                    'buildType'  : variant.buildType.name,
                    'versionName': variant.versionName,
                    'versionCode': variant.versionCode,
                    'packageName': variant.applicationId,
                    'flavorName' : variant.flavorName
            ]

            if (targetProject.hasProperty(PROPERTY_CHANNEL_LIST)) {

                def channelList = new ArrayList<String>()
                def channelListProperty = targetProject.getProperties().get(PROPERTY_CHANNEL_LIST)
                if (channelListProperty != null && channelListProperty.trim().length() > 0) {
                    channelList.addAll(channelListProperty.split(",").collect { it.trim() })
                }
                def extraInfo = null;
                def extraInfoString = targetProject.getProperties().get(PROPERTY_EXTRA_INFO)
                if (extraInfoString != null && extraInfoString.trim().length() > 0) {
                    def keyValues = extraInfoString.split(",").collect {
                        it.trim()
                    }
                    extraInfo = keyValues.findAll {
                        it.split(":").size() == 2
                    }.collectEntries([:]) { keyValue ->
                        def data = keyValue.split(":")
                        [data[0], data[1]]
                    }
                }
                channelList.each { channel ->
                    generateChannelApk(apkFile, channelOutputFolder, nameVariantMap, channel, extraInfo, null)
                }

            } else if (targetProject.hasProperty(PROPERTY_CONFIG_FILE)) {

                def configFile = new File(targetProject.getProperties().get(PROPERTY_CONFIG_FILE))

                if (!configFile.exists()) {
                    project.logger.warn("config file does not exist")
                    return
                }

                generateChannelApkByConfigFile(configFile, apkFile, channelOutputFolder, nameVariantMap)

            } else if (targetProject.hasProperty(PROPERTY_CHANNEL_FILE)) {

                def channelFile = new File(targetProject.getProperties().get(PROPERTY_CHANNEL_FILE))

                if (!channelFile.exists()) {
                    project.logger.warn("channel file does not exist")
                    return
                }

                generateChannelApkByChannelFile(channelFile, apkFile, channelOutputFolder, nameVariantMap)

            } else if (extension.configFile instanceof File) {

                if (!extension.configFile.exists()) {
                    project.logger.warn("config file does not exist")
                    return
                }

                generateChannelApkByConfigFile(extension.configFile, apkFile, channelOutputFolder, nameVariantMap)

            } else if (extension.channelFile instanceof File) {

                if (!extension.channelFile.exists()) {
                    project.logger.warn("channel file does not exist")
                    return
                }

                generateChannelApkByChannelFile(extension.channelFile, apkFile, channelOutputFolder, nameVariantMap)
            } else if (extension.variantConfigFileName != null && extension.variantConfigFileName.length() > 0) {
                List<File> locations = new ArrayList<>()
                locations.add(new File(project.projectDir, "src" + File.separator + variant.name))
                locations.add(new File(project.projectDir, "src" + File.separator + variant.flavorName))
                locations.add(new File(project.projectDir, "src" + File.separator + variant.buildType.name))
                locations.add(new File(project.projectDir, "src" + File.separator + "main"))


                boolean isFindConfigFile = false
                locations.each { file ->
                    if (isFindConfigFile) {
                        return true
                    }
                    if (file.exists()) {
                        File configFile = new File(file, extension.variantConfigFileName)
                        if (configFile.exists()) {
                            generateChannelApkByConfigFile(configFile, apkFile, channelOutputFolder, nameVariantMap)
                            isFindConfigFile = true
                            project.logger.error("[Walle] Using config file : " + configFile)
                        }
                    }
                }
                if (!isFindConfigFile) {
                    project.logger.error("[Walle] config file does not exist")
                    project.logger.error("[Walle] please put the file in the follow locations [Descending order of priority]")
                    locations.each { file ->
                        project.logger.error("[Walle]   " + file.absolutePath)
                    }

                }
            }

        }


        targetProject.logger.lifecycle("APK Signature Scheme v2 Channel Maker takes about " + (
                System.currentTimeMillis() - startTime) + " milliseconds");
    }

    def generateChannelApkByConfigFile(File configFile, File apkFile, File channelOutputFolder, nameVariantMap) {
        WalleConfig config = new Gson().fromJson(new InputStreamReader(new FileInputStream(configFile), "UTF-8"), WalleConfig.class)
        def defaultExtraInfo = config.getDefaultExtraInfo()
        config.getChannelInfoList().each { channelInfo ->
            def extraInfo = channelInfo.extraInfo
            if (!channelInfo.excludeDefaultExtraInfo) {
                switch (config.defaultExtraInfoStrategy) {
                    case WalleConfig.STRATEGY_IF_NONE:
                        if (extraInfo == null) {
                            extraInfo = defaultExtraInfo
                        }
                        break;
                    case WalleConfig.STRATEGY_ALWAYS:
                        def temp = new HashMap<String, String>()
                        if (defaultExtraInfo != null) {
                            temp.putAll(defaultExtraInfo)
                        }
                        if (extraInfo != null) {
                            temp.putAll(extraInfo)
                        }
                        extraInfo = temp
                        break;
                    default:
                        break;
                }
            }

            generateChannelApk(apkFile, channelOutputFolder, nameVariantMap, channelInfo.channel, extraInfo, channelInfo.alias)
        }
    }

    def generateChannelApkByChannelFile(File channelFile, File apkFile, File channelOutputFolder, nameVariantMap) {
        getChannelListFromFile(channelFile).each { channel -> generateChannelApk(apkFile, channelOutputFolder, nameVariantMap, channel, null, null) }
    }

    static def getChannelListFromFile(File channelFile) {
        def channelList = []
        channelFile.eachLine { line ->
            def lineTrim = line.trim()
            if (lineTrim.length() != 0 && !lineTrim.startsWith("#")) {
                def channel = line.split("#").first().trim()
                if (channel.length() != 0)
                    channelList.add(channel)
            }
        }
        return channelList
    }

    def generateChannelApk(File apkFile, File channelOutputFolder, Map nameVariantMap, channel, extraInfo, alias) {
        Extension extension = Extension.getConfig(targetProject);

        def buildTime = new SimpleDateFormat('yyyyMMdd-HHmmss').format(new Date());
        def channelName = alias == null ? channel : alias

        String fileName = apkFile.getName();
        if (fileName.endsWith(DOT_APK)) {
            fileName = fileName.substring(0, fileName.lastIndexOf(DOT_APK));
        }

        String apkFileName = "${fileName}-${channelName}${DOT_APK}";

        File channelApkFile = new File(apkFileName, channelOutputFolder);
        FileUtils.copyFile(apkFile, channelApkFile);
        ChannelWriter.put(channelApkFile, channel, extraInfo)

        nameVariantMap.put("buildTime", buildTime);
        nameVariantMap.put('channel', channelName);
        nameVariantMap.put('fileSHA1', getFileHash(channelApkFile));
        if (extension.apkFileNameFormat != null && extension.apkFileNameFormat.length() > 0) {
            def newApkFileName = new SimpleTemplateEngine().createTemplate(extension.apkFileNameFormat).make(nameVariantMap).toString()
            if (!newApkFileName.contentEquals(apkFileName)) {
                channelApkFile.renameTo(new File(newApkFileName, channelOutputFolder))
            }
        }
    }

    def checkV2Signature(File apkFile) {
        FileInputStream fIn;
        FileChannel fChan;
        try {
            fIn = new FileInputStream(apkFile);
            fChan = fIn.getChannel();
            long fSize = fChan.size();
            ByteBuffer byteBuffer = ByteBuffer.allocate((int) fSize);
            fChan.read(byteBuffer);
            byteBuffer.rewind();

            DataSource dataSource = new ByteBufferDataSource(byteBuffer);

            ApkVerifier apkVerifier = new ApkVerifier();
            ApkVerifier.Result result = apkVerifier.verify(dataSource, 0);
            if (!result.verified || !result.verifiedUsingV2Scheme) {
                throw new GradleException("${apkFile} has no v2 signature in Apk Signing Block!");
            }
        } catch (IOException ignore) {
            ignore.printStackTrace();
        } finally {
            IOUtils.closeQuietly(fChan);
            IOUtils.closeQuietly(fIn);
        }
    }

    private static String getFileHash(File file) throws IOException {
        HashCode hashCode;
        HashFunction hashFunction = Hashing.sha1();
        if (file.isDirectory()) {
            hashCode = hashFunction.hashString(file.getPath(), Charsets.UTF_16LE);
        } else {
            hashCode = Files.hash(file, hashFunction);
        }
        return hashCode.toString();
    }

    SigningConfig getSigningConfig(BaseVariant variant) {
        return variant.buildType.signingConfig == null ? variant.mergedFlavor.signingConfig : variant.buildType.signingConfig;
    }
}
