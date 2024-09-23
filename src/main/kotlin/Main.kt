import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
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

fun processImages(files: List<File>, saveFolder: File, targetSizeKb: Int = 120, targetWidth: Int = 640) {
    files.forEach { file ->
        val outputFile = File(saveFolder, "compressed_${file.name}")
        compressImage(file, outputFile, targetSizeKb, targetWidth)
        println("Сжатие завершено для файла: ${file.name}, результат: ${outputFile.name}")
    }
}

@Composable
@Preview
fun App() {
    var selectedImages by remember { mutableStateOf<List<File>>(emptyList()) }
    var saveFolder by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf("Выберите фотографии и папку для сохранения.") }

    MaterialTheme {
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

            Button(onClick = {
                if (selectedImages.isNotEmpty() && saveFolder != null) {
                    processImages(selectedImages, saveFolder!!)
                    message = "Обработка завершена!"
                } else {
                    message = "Выберите фотографии и папку для сохранения."
                }
            }, modifier = Modifier.padding(top = 8.dp)) {
                Text("Запустить обработку")
            }

            BasicText(text = message, modifier = Modifier.padding(top = 16.dp))
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
        icon = painterResource("Logo.jpg")
    ) {
        App()
    }
}
