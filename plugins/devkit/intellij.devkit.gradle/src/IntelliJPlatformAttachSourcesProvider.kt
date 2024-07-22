// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.devkit.gradle

import com.intellij.codeInsight.AttachSourcesProvider
import com.intellij.codeInsight.AttachSourcesProvider.AttachSourcesAction
import com.intellij.jarFinder.InternetAttachSourceProvider
import com.intellij.java.library.MavenCoordinates
import com.intellij.java.library.getMavenCoordinates
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.util.ActionCallback
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiFile
import org.jetbrains.idea.devkit.projectRoots.IntelliJPlatformProduct
import org.jetbrains.idea.devkit.run.ProductInfo
import org.jetbrains.idea.devkit.run.loadProductInfo
import org.jetbrains.plugins.gradle.execution.build.CachedModuleDataFinder
import org.jetbrains.plugins.gradle.util.GradleDependencySourceDownloader
import java.io.File
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.pathString

internal enum class ApiSourceArchive(
  val id: String,
  val displayName: String,
  val archiveName: String,
) {
  CSS("com.intellij.css", "CSS plugin API", "src_css-api.zip"),
  DATABASE("com.intellij.database", "Database plugin API", "src_database-openapi.zip"),
  JAM("com.intellij.java", "JAM plugin API", "src_jam-openapi.zip"),
  JAVAEE("com.intellij.javaee", "JavaEE plugin API", "src_javaee-openapi.zip"),
  PERSISTENCE("com.intellij.persistence", "Persistence plugin API", "src_persistence-openapi.zip"),
  SPRING("com.intellij.spring", "Spring plugin API", "src_spring-openapi.zip"),
  SPRING_BOOT("com.intellij.spring.boot", "SpringBoot plugin API", "src_spring-boot-openapi.zip"),
  TOMCAT("Tomcat", "Tomcat plugin API", "src_tomcat.zip"),
  LSP("LSP", "IntelliJ Platform LSP-API", "src_lsp-openapi.zip"),
}

/**
 * Attaches sources to the IntelliJ Platform dependencies in projects using IntelliJ Platform Gradle Plugin 2.x.
 * Some IDEs, like IntelliJ IDEA Ultimate or PhpStorm, don't provide sources for artifacts published to IntelliJ Repository.
 * To handle such a case, IntelliJ IDEA Community sources are attached.
 */
internal class IntelliJPlatformAttachSourcesProvider : AttachSourcesProvider {

  override fun getActions(orderEntries: MutableList<out LibraryOrderEntry>, psiFile: PsiFile) =
    orderEntries
      .mapNotNull { it.library?.getMavenCoordinates() }
      .firstNotNullOfOrNull { coordinates -> createAction(coordinates, psiFile) }
      .let { listOfNotNull(it) }

  private fun createAction(coordinates: MavenCoordinates, psiFile: PsiFile): AttachSourcesAction? {
    val product = IntelliJPlatformProduct.fromMavenCoordinates(coordinates.groupId, coordinates.artifactId)
                  ?: IntelliJPlatformProduct.fromCdnCoordinates(coordinates.groupId, coordinates.artifactId)

    // IntelliJ Platform dependency, such as `com.jetbrains.intellij.idea:ideaIC:2023.2.7` or `idea:ideaIC:2023.2.7`
    val isIntelliJPlatform = product != null

    // IntelliJ Platform bundled plugin, such as `localIde:IC:2023.2.7+445`
    val isLocalIntelliJPlatform = coordinates.groupId == "localIde"

    // IntelliJ Platform bundled plugin, such as `bundledPlugin:Git4Idea:2023.2.7+445`
    val isBundledPlugin = coordinates.groupId == "bundledPlugin"

    return when {
      isIntelliJPlatform -> resolveIntelliJPlatformAction(psiFile, requireNotNull(product), coordinates.version)
      isLocalIntelliJPlatform -> createAttachLocalPlatformSourcesAction(psiFile, coordinates)
      isBundledPlugin -> createAttachBundledPluginSourcesAction(psiFile, coordinates)
      else -> null
    }
  }

  /**
   * Resolve and attach IntelliJ Platform sources to the currently handled dependency in a requested version.
   *
   * Requests PyCharm Community sources if PyCharm Community or PyCharm Professional.
   * Requests IntelliJ IDEA Ultimate sources if IntelliJ IDEA Ultimate 2024.2+.
   * In all other cases, requests IntelliJ IDEA Community sources.
   *
   * If the LSP API class is detected while targeting IntelliJ IDEA Ultimate <2024.2, attaches bundled LSP API sources archive file.
   *
   * @param psiFile The PSI file that represents the currently handled class.
   * @param product The IntelliJ Platform defined by the dependency entry.
   * @param version The version of the product.
   */
  private fun resolveIntelliJPlatformAction(
    psiFile: PsiFile,
    product: IntelliJPlatformProduct,
    version: String,
  ): AttachSourcesAction? {
    val productInfo = resolveProductInfo(psiFile) ?: return null
    val majorVersion = productInfo.buildNumber.substringBefore('.').toInt()

    val productCoordinates =
      when (product) {
        // For PyCharm Community and PyCharm Professional, we use PC sources.
        IntelliJPlatformProduct.PYCHARM, IntelliJPlatformProduct.PYCHARM_PC -> IntelliJPlatformProduct.PYCHARM_PC

        // IntelliJ IDEA Ultimate has sources published since 242; otherwise we use IC.
        IntelliJPlatformProduct.IDEA -> when {
          majorVersion >= 242 -> IntelliJPlatformProduct.IDEA
          else -> IntelliJPlatformProduct.IDEA_IC
        }

        // Any other IntelliJ Platform should use IC
        else -> IntelliJPlatformProduct.IDEA_IC
      }.mavenCoordinates ?: return null

    // When targeting IntelliJ IDEA Ultimate, it is possible to attach LSP module sources.
    // If the compiled class belongs to `com/intellij/platform/lsp/`, suggest attaching the relevant ZIP archive with LSP sources.
    val isLspApi = with(psiFile.virtualFile.path.substringAfter('!')) {
      when {
        startsWith("/com/intellij/platform/lsp/impl/") -> false
        startsWith("/com/intellij/platform/lsp/") -> true
        else -> false
      } && product == IntelliJPlatformProduct.IDEA // LSP API sources are provided only with IU
    }

    return when {
      // We're handing LSP API class, but IU is lower than 242 -> attach a standalone sources file
      isLspApi && majorVersion < 242 -> createAttachSourcesArchiveAction(psiFile, ApiSourceArchive.LSP)

      // Create the actual IntelliJ Platform sources attaching action
      else -> createAttachPlatformSourcesAction(psiFile, productCoordinates, version)
    }
  }

  /**
   * Creates an action to attach sources of a local IntelliJ Platform.
   *
   * @param psiFile The PSI file representing the currently handled class.
   * @param coordinates The Maven coordinates of the IntelliJ Platform whose sources need to be attached.
   */
  private fun createAttachLocalPlatformSourcesAction(psiFile: PsiFile, coordinates: MavenCoordinates): AttachSourcesAction? {
    val product = IntelliJPlatformProduct.fromProductCode(coordinates.artifactId) ?: return null
    val version = coordinates.version.substringBefore('+')

    return resolveIntelliJPlatformAction(psiFile, product, version)
  }

  /**
   * Creates an action to attach sources of bundled plugins for the IntelliJ Platform.
   *
   * @param psiFile The PSI file that represents the currently handled class.
   * @param coordinates The Maven coordinates of the bundled plugin whose sources need to be attached.
   */
  private fun createAttachBundledPluginSourcesAction(psiFile: PsiFile, coordinates: MavenCoordinates): AttachSourcesAction? {
    val productInfo = resolveProductInfo(psiFile) ?: return null
    val product = IntelliJPlatformProduct.fromProductCode(productInfo.productCode) ?: return null
    val version = coordinates.version.substringBefore('+')
    val apiSourceArchive = ApiSourceArchive.entries.firstOrNull { it.id == coordinates.artifactId }

    return createAttachSourcesArchiveAction(psiFile, apiSourceArchive)
           ?: resolveIntelliJPlatformAction(psiFile, product, version)
  }

  /**
   * Attach the provided sources archive.
   *
   * @param psiFile The PSI file that represents the currently handled class.
   * @param apiSourceArchive API sources archive metadata.
   */
  private fun createAttachSourcesArchiveAction(psiFile: PsiFile, apiSourceArchive: ApiSourceArchive?): AttachSourcesAction? {
    if (apiSourceArchive == null) {
      return null
    }

    return resolveSourcesArchive(psiFile, apiSourceArchive.archiveName)?.let {
      object : AttachSourcesAction {

        override fun getName() = DevKitGradleBundle.message("attachSources.api.action.name", apiSourceArchive.displayName)

        override fun getBusyText() = DevKitGradleBundle.message("attachSources.api.action.busyText", apiSourceArchive.displayName)

        override fun perform(orderEntries: MutableList<out LibraryOrderEntry>): ActionCallback {
          val executionResult = ActionCallback()

          attachSources(it.toFile(), orderEntries) {
            executionResult.setDone()
          }

          return executionResult
        }
      }
    }
  }

  /**
   * Creates an action to attach IntelliJ Platform sources to a library within the project.
   *
   * @param psiFile The PSI file that represents the currently handled class.
   * @param productCoordinates The Maven coordinates of the IntelliJ Platform whose sources we load.
   * @param version The version of the product.
   */
  private fun createAttachPlatformSourcesAction(psiFile: PsiFile, productCoordinates: String, version: String) =
    object : AttachSourcesAction {
      override fun getName() = DevKitGradleBundle.message("attachSources.intellijPlatform.action.name")

      override fun getBusyText() = DevKitGradleBundle.message("attachSources.intellijPlatform.action.busyText")

      override fun perform(orderEntries: MutableList<out LibraryOrderEntry>): ActionCallback {
        val externalProjectPath = CachedModuleDataFinder.getGradleModuleData(orderEntries.first().ownerModule)?.directoryToRunTask
                                  ?: return ActionCallback.REJECTED

        val executionResult = ActionCallback()
        val project = psiFile.project
        val sourceArtifactNotation = "$productCoordinates:$version:sources"

        GradleDependencySourceDownloader
          .downloadSources(project, name, sourceArtifactNotation, externalProjectPath)
          .whenComplete { path, error ->
            if (error != null) {
              executionResult.setRejected()
            }
            else {
              attachSources(path, orderEntries) {
                executionResult.setDone()
              }
            }
          }

        return executionResult
      }
    }

  /**
   * Attaches sources jar to the specified libraries and executes the provided block of code.
   */
  private fun attachSources(path: File, orderEntries: MutableList<out LibraryOrderEntry>, block: () -> Unit) {
    ApplicationManager.getApplication().invokeLater {
      InternetAttachSourceProvider.attachSourceJar(path, orderEntries.mapNotNull { it.library })
      block()
    }
  }

  /**
   * Resolve the [ProductInfo] of the current IntelliJ Platform.
   */
  private fun resolveProductInfo(psiFile: PsiFile): ProductInfo? {
    val jarPath = Path(psiFile.virtualFile.path.substringBefore('!'))
    return generateSequence(jarPath) { it.parent }
      .takeWhile { it != it.root }
      .firstNotNullOfOrNull { loadProductInfo(it.pathString) }
  }

  /**
   * Resolve the [ProductInfo] of the current IntelliJ Platform.
   */
  private fun resolveSourcesArchive(psiFile: PsiFile, archiveName: String): Path? {
    val path = VfsUtilCore.getVirtualFileForJar(psiFile.virtualFile)?.toNioPath() ?: return null
    return generateSequence(path) { it.parent }
      .takeWhile { it != it.root }
      .firstNotNullOfOrNull { it.resolve("lib/src/$archiveName").takeIf(Path::exists) }
  }
}
