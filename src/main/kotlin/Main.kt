import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.*
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.ImageWriter
import javax.swing.JFileChooser

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

fun compressImage(inputFile: File, outputFile: File, targetSizeKb: Int = 120, targetWidth: Int = 640) {
    val originalImage: BufferedImage = ImageIO.read(inputFile)
    val resizedImage = resizeImage(originalImage, targetWidth)
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
        quality -= 0.005f
    } while (compressed!!.size > targetSizeKb * 1024 && quality > 0)
    outputFile.writeBytes(compressed)
}

suspend fun processImages(
    files: List<File>,
    saveFolder: File,
    targetSizeKb: Int = 120,
    targetWidth: Int = 640,
    onProgress: (Float) -> Unit,
    onError: (String) -> Unit
) {
    try {
        files.forEachIndexed { index, file ->
            val outputFile = File(saveFolder, "compressed_${file.name}")
            compressImage(file, outputFile, targetSizeKb, targetWidth)
            onProgress((index + 1).toFloat() / files.size)
            delay(200)
        }
    } catch (e: Exception) {
        onError("Ошибка при обработке файлов: ${e.message}")
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

@Composable
@Preview
fun App() {
    var selectedImages by remember { mutableStateOf<List<File>>(emptyList()) }
    var saveFolder by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf("Выберите фотографии и папку для сохранения.") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }

    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Button(onClick = {
                    val files = chooseImages()
                    if (files.isNotEmpty()) {
                        selectedImages = files
                        message = "Выбрано ${files.size} фотографий."
                    }
                }) {
                    Text("Выбрать фотографии")
                }

                Button(onClick = {
                    val folder = chooseFolder()
                    if (folder != null) {
                        saveFolder = folder
                        message = "Выбрана папка для сохранения: ${folder.path}."
                    }
                }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Выбрать папку для сохранения")
                }

                Button(
                    onClick = {
                        if (selectedImages.isEmpty()) {
                            errorMessage = "Не выбраны фотографии для обработки."
                        } else if (saveFolder == null) {
                            errorMessage = "Не выбрана папка для сохранения."
                        } else {
                            isProcessing = true
                            CoroutineScope(Dispatchers.Default).launch {
                                processImages(
                                    selectedImages,
                                    saveFolder!!,
                                    onProgress = { p ->
                                        progress = p
                                    },
                                    onError = { error ->
                                        errorMessage = error
                                        isProcessing = false
                                    }
                                )
                                isProcessing = false
                                message = "Обработка завершена!"
                            }
                        }
                    },
                    enabled = !isProcessing,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text("Запустить обработку")
                }

                if (isProcessing) {
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                    )
                    BasicText("Обработка: ${(progress * 100).toInt()}%", modifier = Modifier.padding(top = 8.dp))
                } else {
                    BasicText(text = message, modifier = Modifier.padding(top = 16.dp))
                }

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

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "ImageOptimizator",
        icon = painterResource("Logo.ico")
    ) {
        App()
    }
}
