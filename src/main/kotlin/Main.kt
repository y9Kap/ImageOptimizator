import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import kotlinx.coroutines.*
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.*
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.ImageWriter
import javax.swing.JFileChooser
import kotlin.math.cos
import kotlin.math.sin
import java.awt.Graphics2D
import kotlin.math.abs

fun getImageOrientation(inputFile: File): Int {
    val metadata = ImageMetadataReader.readMetadata(inputFile)
    val exifDir = metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
    return exifDir?.getInt(ExifIFD0Directory.TAG_ORIENTATION) ?: 1
}

fun resizeImage(image: BufferedImage, targetWidth: Int): BufferedImage {
    val aspectRatio = image.height.toDouble() / image.width.toDouble()
    val targetHeight = (targetWidth * aspectRatio).toInt()
    val scaledImage = image.getScaledInstance(targetWidth, targetHeight, Image.SCALE_SMOOTH)
    val resizedImage = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
    val graphics = resizedImage.createGraphics()
    graphics.drawImage(scaledImage, 0, 0, null)
    graphics.dispose()
    return resizedImage
}

fun rotateImage(image: BufferedImage, angle: Double): BufferedImage {
    val radians = Math.toRadians(angle)
    val sin = abs(sin(radians))
    val cos = abs(cos(radians))
    val newWidth = (image.width * cos + image.height * sin).toInt()
    val newHeight = (image.width * sin + image.height * cos).toInt()

    val rotatedImage = BufferedImage(newWidth, newHeight, image.type)
    val g2d: Graphics2D = rotatedImage.createGraphics()
    g2d.translate((newWidth - image.width) / 2, (newHeight - image.height) / 2)
    g2d.rotate(radians, (image.width / 2).toDouble(), (image.height / 2).toDouble())
    g2d.drawRenderedImage(image, null)
    g2d.dispose()

    return rotatedImage
}

fun getRotationAngle(orientation: Int): Double {
    return when (orientation) {
        1, 2 -> 0.0
        3, 4 -> 180.0
        5, 7 -> -90.0
        6, 8 -> 90.0
        else -> 0.0
    }
}

fun compressImage(inputFile: File, outputFile: File, targetSizeKb: Int = 120, targetWidth: Int = 640) {
    val originalImage: BufferedImage = ImageIO.read(inputFile)
    val orientation = getImageOrientation(inputFile)
    val rotationAngle = getRotationAngle(orientation)

    val image = rotateImage(originalImage, rotationAngle)
    val resizedImage = resizeImage(image, targetWidth)
    val writer: ImageWriter = ImageIO.getImageWritersByFormatName("jpg").next()
    val baos = ByteArrayOutputStream()
    var quality = 1.0f
    var compressed: ByteArray?
    do {
        baos.reset()
        val param: ImageWriteParam = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality
        }
        writer.output = ImageIO.createImageOutputStream(baos)
        writer.write(null, IIOImage(resizedImage, null, null), param)
        compressed = baos.toByteArray()
        quality -= 0.001f
    } while (compressed!!.size > targetSizeKb * 1024 && quality > 0)
    outputFile.writeBytes(compressed)
}

suspend fun processImages(
    files: List<File>,
    saveFolder: File,
    targetSizeKb: Int = 120,
    targetWidth: Int = 640,
    onError: (String) -> Unit
) {
    coroutineScope {
        files.map { file ->
            async(Dispatchers.IO) {
                try {
                    val outputFile = File(saveFolder, "${file.nameWithoutExtension}.jpg")
                    compressImage(file, outputFile, targetSizeKb, targetWidth)
                } catch (e: Exception) {
                    onError("Ошибка при обработке файла ${file.name}: ${e.message}")
                    saveFolder.listFiles()?.forEach { it.delete() }
                    return@async
                }
            }
        }.awaitAll()
    }
}

@Composable
fun ErrorDialog(errorMessage: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ошибка") },
        text = { Text(errorMessage) },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("ОК")
            }
        }
    )
}

fun restoreFileOrderInShuffledFolder(originalFolder: File, shuffledFolder: File) {
    val originalFiles = originalFolder.listFiles()?.toList() ?: emptyList()
    val shuffledFiles = shuffledFolder.listFiles()?.toList() ?: emptyList()

    val tempFolder = File(shuffledFolder, "temp")
    tempFolder.mkdirs()

    originalFiles.forEach { originalFile ->
        val shuffledFile = shuffledFiles.find { it.nameWithoutExtension == originalFile.nameWithoutExtension }

        shuffledFile?.renameTo(File(tempFolder, "${shuffledFile.nameWithoutExtension}.jpg"))
    }

    shuffledFolder.listFiles()?.forEach { it.delete() }

    tempFolder.listFiles()?.forEach { tempFile ->
        val newFile = File(shuffledFolder, "${tempFile.nameWithoutExtension}.jpg")
        tempFile.renameTo(newFile)
    }

    tempFolder.deleteRecursively()
}

@Composable
@Preview
fun App() {
    var selectedImages by remember { mutableStateOf<List<File>>(emptyList()) }
    var saveFolder by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf("Выберите фотографии и папку для сохранения.") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    var saveOrder by remember { mutableStateOf(false) }

    val (initialWidth, initialSizeKb) = loadConfig()

    var targetWidth by rememberSaveable { mutableStateOf(initialWidth) }
    var targetSizeKb by rememberSaveable { mutableStateOf(initialSizeKb) }

    val focusManager = LocalFocusManager.current

    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        focusManager.clearFocus()
                    })
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Row {
                    OutlinedTextField(
                        value = targetSizeKb,
                        onValueChange = {
                            targetSizeKb = it
                            saveConfig(targetWidth, targetSizeKb)
                        },
                        label = { Text("Размер (КБ)") },
                        singleLine = true,
                        modifier = Modifier.width(150.dp)
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    OutlinedTextField(
                        value = targetWidth,
                        onValueChange = {
                            targetWidth = it
                            saveConfig(targetWidth, targetSizeKb)
                        },
                        label = { Text("Ширина (px)") },
                        singleLine = true,
                        modifier = Modifier.width(150.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = saveOrder,
                        onCheckedChange = { saveOrder = it }
                    )
                    Text("Сохранить порядок", modifier = Modifier.padding(start = 8.dp))
                }

                Spacer(modifier = Modifier.height(50.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            val files = chooseImages()
                            if (files.isNotEmpty()) {
                                selectedImages = files
                                message = "Выбрано ${files.size} фотографий."
                            }
                        },
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                    ) {
                        Text("Выбрать фотографии")
                    }

                    Button(
                        onClick = {
                            val imageFiles = chooseImageFolder()
                            if (!imageFiles.isNullOrEmpty()) {
                                selectedImages = imageFiles
                                message = "Выбрана папка с ${imageFiles.size} фотографиями."
                            } else {
                                message = "В папке нет фотографий."
                            }
                        },
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                    ) {
                        Text("Выбрать папку с фотографиями")
                    }
                }

                Button(
                    onClick = {
                        val folder = chooseFolder()
                        if (folder != null) {
                            saveFolder = folder
                            message = "Выбрана папка для сохранения: ${folder.path}."
                        }
                    },
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Text("Выбрать папку для сохранения")
                }

                Button(
                    onClick = {
                        if (selectedImages.isEmpty()) {
                            errorMessage = "Не выбраны фотографии для обработки."
                        } else if (saveFolder == null) {
                            errorMessage = "Не выбрана папка для сохранения."
                        } else if (targetWidth.toIntOrNull() == null || targetSizeKb.toIntOrNull() == null) {
                            errorMessage = "Некорректные значения для ширины или размера."
                        } else {
                            isProcessing = true
                            message = "Обработка начата..."
                            CoroutineScope(Dispatchers.Default).launch {
                                processImages(
                                    selectedImages,
                                    saveFolder!!,
                                    targetSizeKb = targetSizeKb.toInt(),
                                    targetWidth = targetWidth.toInt(),
                                    onError = { error ->
                                        errorMessage = error
                                        isProcessing = false
                                    }
                                )
                                if (saveOrder) {
                                    val originalFolder = getFirstImageDirectory(selectedImages)
                                    restoreFileOrderInShuffledFolder(originalFolder, saveFolder!!)
                                }
                                isProcessing = false
                                message = "Обработка завершена!"
                            }
                        }
                    },
                    enabled = !isProcessing,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Text("Запустить обработку")
                }
                BasicText(text = message, modifier = Modifier.padding(top = 16.dp))
                errorMessage?.let { error ->
                    ErrorDialog(errorMessage = error) {
                        errorMessage = null
                    }
                }
            }
        }
    }
}

fun chooseImages(): List<File> {
    val fileDialog = FileDialog(Frame(), "Выберите изображения", FileDialog.LOAD).apply {
        isMultipleMode = true
        file = "*.jpg;*.jpeg"
        isVisible = true
    }
    return fileDialog.files?.toList() ?: emptyList()
}

fun chooseFolder(): File? {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Выберите папку для сохранения"
    }
    val result = chooser.showOpenDialog(null)
    return if (result == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile
    } else {
        null
    }
}

fun getFirstImageDirectory(files: List<File>): File {
    return files.first().parentFile
}

fun chooseImageFolder(): List<File>? {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Выберите папку с фотографиями"
    }

    val result = chooser.showOpenDialog(null)
    return if (result == JFileChooser.APPROVE_OPTION) {
        val selectedFolder = chooser.selectedFile

        selectedFolder.listFiles { _, name ->
            name.endsWith(".jpg", ignoreCase = true) ||
                    name.endsWith(".jpeg", ignoreCase = true)
        }?.toList()
    } else {
        null
    }
}


fun saveConfig(targetWidth: String, targetSizeKb: String) {
    val properties = Properties()
    properties["targetWidth"] = targetWidth
    properties["targetSizeKb"] = targetSizeKb

    val configFile = File("config.properties")
    configFile.outputStream().use { properties.store(it, null) }
}

fun loadConfig(): Pair<String, String> {
    val configFile = File("config.properties")
    if (!configFile.exists()) return "640" to "120"

    val properties = Properties().apply {
        load(configFile.inputStream())
    }

    val targetWidth = properties.getProperty("targetWidth", "640")
    val targetSizeKb = properties.getProperty("targetSizeKb", "120")

    return targetWidth to targetSizeKb
}

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "ImageOptimizator",
        icon = painterResource("Logo.ico")
    ) {
        App()
    }
}
