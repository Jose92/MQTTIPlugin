package io.github.jose92

import com.android.build.gradle.BaseExtension
import io.github.jose92.poko.MqttInfo
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.jvm.tasks.Jar
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * Plugin Gradle pour gérer la création et la signature des JAR, la génération de fichiers DEX, et l'installation sur un appareil Android.
 */
class MQTTPlugin : Plugin<Project> {

    private var pluginName = ""
    private var versionFile = ""
    private var rootProjectName = ""
    private var signrootProjectName = ""
    private var signToolsDir: String? = null
    private var certsDir: String? = null
    private var deviceId: String? = null
    private var pkgMqtt: String? = ""
    private var versionPlugin = -1

    /**
     * Applique le plugin au projet spécifié.
     *
     * @param project Le projet Gradle auquel le plugin est appliqué.
     */
    override fun apply(project: Project) {
        val myInfo = project.extensions.create("MqttInfo", MqttInfo::class.java)
        project.afterEvaluate {

            val androidExtension = project.extensions.findByType(BaseExtension::class.java)
            pluginName = myInfo.pluginName
            versionFile = "${project.rootDir}/version.txt"
            versionPlugin = myInfo.versionPlugin
            rootProjectName = "${project.rootDir}/$pluginName.jar"
            signrootProjectName = "$rootProjectName.idsig"
            signToolsDir = myInfo.signTools
            certsDir = myInfo.signCerts
            deviceId = myInfo.deviceId
            pkgMqtt = myInfo.packageMqtt

            if (pkgMqtt.isNullOrEmpty()) {
                throw IllegalArgumentException("Le package MQTT (pkgMqtt) ne peut pas être nul ou vide.")
            }

            project.cleanWorkspace()

            project.tasks.register("fatJar", Jar::class.java) { jar ->
                project.logger.lifecycle("========= Construction du JAR =========")
                jar.dependsOn("cleanWK", "assemble")
                jar.mustRunAfter("assembleRelease")
                jar.archiveFileName.set("app.jar")
                jar.from(project.file("build/intermediates/javac/release"))
            }

            androidExtension?.let {
                project.generateDexFile(it)
            }

            project.tasks.register("buildWithChecks") { task ->
                task.dependsOn("createDex")
                task.mustRunAfter("createDex")
                task.doLast {
                    project.logger.lifecycle("========= Create Version TXT =========")
                    project.createVersionTxt()
                    project.logger.lifecycle("========= Create JAR =========")
                    project.createJar()

                    listOf(
                        File("${project.rootDir}/classes.dex"),
                        File(versionFile)
                    ).filter { it.exists() }
                        .forEach { it.delete() }

                    certsDir?.let {
                        project.logger.lifecycle("========= Signature =========")

                        androidExtension?.let { ext ->
                            project.signJarFile(ext, it)
                        }
                        deviceId?.takeIf { it.isNotEmpty() }?.let {
                            project.logger.lifecycle("========= Installation =========")
                            installJarFile()
                        }
                    }
                }?:run{
                    project.logger.lifecycle("========= Aucune Installation =========")
                }
            }
        }
    }

    /**
     * Génère le fichier DEX à partir du JAR en utilisant l'outil d8.
     *
     * @param androidExtension L'extension Android utilisée pour obtenir les informations sur le SDK et les outils de construction.
     */
    private fun Project.generateDexFile(androidExtension: BaseExtension) {
        tasks.register("createDex") { task ->
            task.dependsOn("fatJar")
            task.mustRunAfter("fatJar")
            task.doLast {
                logger.lifecycle("========= Génération du fichier DEX =========")
                val sdkPath = androidExtension.sdkDirectory
                val buildTools = androidExtension.buildToolsVersion
                val d8File = File("$sdkPath/build-tools/$buildTools", "d8")
                executeCommand(
                    listOf(
                        d8File.absolutePath,
                        "--no-desugaring",
                        "--output", rootDir.absolutePath,
                        file("build/libs/app.jar").absolutePath
                    )
                )?.waitFor()
            }
        }
    }

    /**
     * Nettoie le répertoire de travail en supprimant les fichiers générés précédemment.
     */
    private fun Project.cleanWorkspace() {
        tasks.register("cleanWK") { task ->
            task.group = "Custom"
            task.description = "Clean working directory"
            logger.lifecycle("========= Nettoyage du workspace =========")
            listOf(
                File(rootProjectName),
                File("${rootDir}/classes.dex"),
                File(versionFile),
                File(signrootProjectName)
            ).filter { it.exists() }
                .forEach {
                    println("Suppression de $it")
                    it.delete()
                }
        }
    }

    /**
     * Crée un fichier JAR en combinant les fichiers spécifiés.
     */
    private fun Project.createJar() {
        val classesDexFile = file("classes.dex")
        createJarFile(rootProjectName, classesDexFile.name, File(versionFile).name)
    }

    /**
     * Crée un fichier JAR en ajoutant les fichiers spécifiés.
     *
     * @param jarFileName Le nom du fichier JAR à créer.
     * @param file1Path Le chemin du premier fichier à ajouter au JAR.
     * @param file2Path Le chemin du deuxième fichier à ajouter au JAR.
     * @throws IOException Si une erreur d'entrée/sortie se produit lors de la création du JAR.
     */
    @Throws(IOException::class)
    private fun createJarFile(jarFileName: String, file1Path: String, file2Path: String) {
        FileOutputStream(jarFileName).use { fileOutputStream ->
            JarOutputStream(fileOutputStream).use { jarOutputStream ->
                addFileToJar(jarOutputStream, file1Path)
                addFileToJar(jarOutputStream, file2Path)
            }
        }
    }

    /**
     * Ajoute un fichier au JAR.
     *
     * @param jarOutputStream Le flux de sortie JAR dans lequel le fichier sera ajouté.
     * @param filePath Le chemin du fichier à ajouter.
     * @throws IOException Si une erreur d'entrée/sortie se produit lors de l'ajout du fichier.
     */
    @Throws(IOException::class)
    private fun addFileToJar(jarOutputStream: JarOutputStream, filePath: String) {
        File(filePath).inputStream().use { fileInputStream ->
            JarEntry(filePath).let { jarEntry ->
                jarOutputStream.putNextEntry(jarEntry)
                fileInputStream.copyTo(jarOutputStream)
                jarOutputStream.closeEntry()
            }
        }
    }

    /**
     * Génère un fichier `version.txt` contenant des informations sur la version du plugin et le temps de construction.
     */
    private fun Project.createVersionTxt() {
        logger.lifecycle("Generating version file...")
        val versionText = """
                Version: $versionPlugin
                Buildtime: ${SimpleDateFormat("dd-MM-yyyy HH:mm:ss").format(Date())}
                PluginName: $pluginName
            """.trimIndent()
        file(versionFile).writeText(versionText)
    }

    /**
     * Signature du fichier JAR en utilisant les outils de signature APK fournis par le SDK Android.
     *
     * @param androidExtension L'extension Android utilisée pour obtenir les informations sur le SDK et les outils de construction.
     * @param keyPath Le chemin vers les clés de signature.
     * @throws IOException Si une erreur d'entrée/sortie se produit lors de la signature du JAR.
     */
    private fun Project.signJarFile(androidExtension: BaseExtension, keyPath: String) {
        logger.lifecycle("========= SIGNJAR =========")
        val sdkPath = androidExtension.sdkDirectory
        val buildTools = androidExtension.buildToolsVersion
        val apkSignerFile = File("$sdkPath/build-tools/$buildTools", "apksigner")
        val exitCode = executeCommand(
            listOf(
                apkSignerFile.absolutePath,
                "sign", "--min-sdk-version", "30",
                "--key", "$keyPath/platform.pk8",
                "--cert", "$keyPath/platform.x509.pem",
                "--in", rootProjectName
            )
        )?.waitFor()?:-1

        if (exitCode != 0) {
            throw GradleException("Erreur lors de la signature du JAR")
        }
    }

    /**
     * Installe le fichier JAR sur un appareil Android connecté.
     * Le fichier JAR est transféré et l'application est redémarrée.
     * La méthode vérifie d'abord que l'appareil est correctement connecté.
     *
     * Les commandes suivantes sont exécutées :
     * 1. `adb root` : Passe l'appareil en mode root.
     * 2. `adb shell am force-stop <pkgMqtt>` : Arrête l'application spécifiée par `pkgMqtt`.
     * 3. `adb push <rootProjectName> /data/data/<pkgMqtt>/files/` : Transfère le fichier JAR vers l'appareil.
     * 4. `adb shell am broadcast -a com.android.mqtt.RESTART -n <pkgMqtt>/.core.MQTTReceiver` : Redémarre l'application.
     *
     * La méthode filtre les commandes pour éliminer celles contenant des éléments nuls.
     *
     * @throws IOException Si une erreur d'entrée/sortie se produit lors de l'exécution des commandes.
     */
    private fun installJarFile() {
        deviceId?.let {
            if (isDeviceConnected(it)) {
                listOf(
                    listOf("adb", "-s", it, "root"),
                    listOf("adb", "-s", it, "shell", "am", "force-stop", pkgMqtt),
                    listOf("adb", "-s", it, "push", rootProjectName, "/data/data/$pkgMqtt/files/"),
                    listOf(
                        "adb",
                        "-s",
                        it,
                        "shell",
                        "am",
                        "broadcast",
                        "-a",
                        "com.android.mqtt.RESTART",
                        "-n",
                        "$pkgMqtt/.core.MQTTReceiver"
                    )
                ).forEach { command ->
                    val exitCode = executeCommand(command)?.waitFor()?:-1
                    if (exitCode != 0) {
                        println("Erreur lors de l'exécution de la commande: ${command.joinToString(" ")}")
                        return
                    }
                }
            } else {
                println("L'appareil avec l'ID $it n'est pas connecté ou ne correspond pas à l'ID spécifié.")
            }
        }
    }

    /**
     * Vérifie si un appareil avec l'ID spécifié est connecté à adb.
     *
     * @param deviceId L'ID de l'appareil à vérifier.
     * @return `true` si l'appareil est connecté, sinon `false`.
     */
    private fun isDeviceConnected(deviceId: String): Boolean {
        return try {
            val command = listOf("adb", "devices")
            val process = executeCommand(command)

            process?.let { proc ->
                val output = proc.inputStream.bufferedReader().use { it.readText() }
                proc.waitFor()
                output.lines().any { line -> line.trim().startsWith(deviceId) }
            } ?: false
        } catch (e: IOException) {
            e.printStackTrace()
            println("Erreur lors de la vérification des appareils connectés : ${e.message}")
            false
        } catch (e: InterruptedException) {
            println("Le processus a été interrompu : ${e.message}")
            false
        }
    }

    /**
     * Exécute une commande système et retourne le processus associé.
     *
     * @param cmd La liste des arguments de la commande à exécuter.
     * @return Le processus résultant de l'exécution de la commande.
     * @throws IOException Si une erreur d'entrée/sortie se produit lors de l'exécution de la commande.
     */
    private fun executeCommand(cmd: List<String?>): Process? {
        return if(cmd.isNotEmpty()) {
            ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
        } else {
            null
        }
    }
}