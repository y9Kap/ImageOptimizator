import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.*
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Graphics2D
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.*
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.ImageWriter
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

fun getImageOrientation(inputFile: File): Int {
    RandomAccessFile(inputFile, "r").use { file ->
        val buffer = ByteArray(2)

        file.read(buffer)
        if (buffer[0] != 0xFF.toByte() || buffer[1] != 0xD8.toByte()) {
            return 1
        }

        while (true) {
            file.read(buffer)
            if (buffer[0] != 0xFF.toByte()) return 1
            if (buffer[1] == 0xE1.toByte()) {
                file.skipBytes(2)
                val exifHeader = ByteArray(6)
                file.read(exifHeader)
                if (!exifHeader.contentEquals("Exif\u0000\u0000".toByteArray())) {
                    return 1
                }
                val tiffHeader = ByteArray(8)
                file.read(tiffHeader)
                val isLittleEndian = tiffHeader[0] == 0x49.toByte() && tiffHeader[1] == 0x49.toByte()

                val numEntries = readShort(file, isLittleEndian)
                for (i in 0 until numEntries) {
                    val tag = readShort(file, isLittleEndian)
                    if (tag == 274.toShort()) {
                        file.skipBytes(6)
                        return readShort(file, isLittleEndian).toInt()
                    } else {
                        file.skipBytes(10)
                    }
                }
                return 1
            } else {
                val length = file.readUnsignedShort()
                file.skipBytes(length - 2)
            }
        }
    }
}

fun readShort(file: RandomAccessFile, isLittleEndian: Boolean): Short {
    val buffer = ByteArray(2)
    file.read(buffer)
    return if (isLittleEndian) {
        ((buffer[1].toInt() and 0xFF) shl 8 or (buffer[0].toInt() and 0xFF)).toShort()
    } else {
        ((buffer[0].toInt() and 0xFF) shl 8 or (buffer[1].toInt() and 0xFF)).toShort()
    }
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

fun rotateImage(image: BufferedImage, orientation: Int): BufferedImage {

    val (angle, mirror) = when (orientation) {
        1 -> 0.0 to false
        2 -> 0.0 to true
        3 -> 180.0 to false
        4 -> 0.0 to true
        5 -> 270.0 to true
        6 -> 90.0 to false
        7 -> 90.0 to true
        8 -> -90.0 to false
        else -> 0.0 to false
    }

    val radians = Math.toRadians(angle)
    val sin = abs(sin(radians))
    val cos = abs(cos(radians))
    val newWidth = (image.width * cos + image.height * sin).toInt()
    val newHeight = (image.width * sin + image.height * cos).toInt()

    val transformedImage = BufferedImage(newWidth, newHeight, image.type)
    val g2d: Graphics2D = transformedImage.createGraphics()

    if (mirror) {
        g2d.scale(-1.0, 1.0)
        g2d.translate(-newWidth.toDouble(), 0.0)
        g2d.drawRenderedImage(transformedImage, null)
    }

    g2d.translate((newWidth - image.width) / 2, (newHeight - image.height) / 2)
    g2d.rotate(radians, (image.width / 2).toDouble(), (image.height / 2).toDouble())
    g2d.drawRenderedImage(image, null)

    g2d.dispose()
    when (orientation) {
        4, 5, 7 -> {
            return rotateImage(transformedImage, 3)
        }
    }
    return transformedImage
}

fun compressImage(inputFile: File, outputFile: File, targetSizeKb: Int = 120, targetWidth: Int = 640) {
    val originalImage: BufferedImage = ImageIO.read(inputFile)
    val orientation = try {
        getImageOrientation(inputFile)
    } catch (_: Throwable) {
        1
    }

    val image = rotateImage(originalImage, orientation)
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
fun errorDialog(errorMessage: String, onDismiss: () -> Unit) {
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
fun optimizePhoto() {
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
                    errorDialog(errorMessage = error) {
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

fun chooseDOCX(): File {
    val fileDialog = FileDialog(Frame(), "Выберите текст дневника", FileDialog.LOAD).apply {
        isMultipleMode = false
        file = "*.docx;*.DOCX"
        isVisible = true
    }
    return fileDialog.files.first()
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


fun chooseFolderToConfig(selectedImages: List<File>) {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Конфигурация и распределение"
    }

    val result = chooser.showOpenDialog(null)
    if (result == JFileChooser.APPROVE_OPTION) {
        val baseDirectory = chooser.selectedFile
        val folders = listOf("На сайт", "Оптимизированные", "Для оптимизации", "Материалы", "Другие фото")
        
        if (!baseDirectory.exists()) {
            baseDirectory.mkdirs()
        }
        
        for (folder in folders) {
            val folderPath = File(baseDirectory, folder)
            if (!folderPath.exists()) {
                folderPath.mkdir()
            }
        }
        val sourceFolder = getSourceFolder(selectedImages)
        val optimizationFolder = File(baseDirectory, "Для оптимизации")
        val otherPhotosFolder = File(baseDirectory, "Другие фото")
        for (file in selectedImages) {
            file.copyTo(File(optimizationFolder, file.name), overwrite = true)
        }
        
        sourceFolder.listFiles()?.forEach { file ->
            if (file.isFile && !selectedImages.contains(file)) {
                file.copyTo(File(otherPhotosFolder, file.name), overwrite = true)
            }
        }
        JOptionPane.showMessageDialog(null, "Задача выполнена успешно!", "Уведомление", JOptionPane.INFORMATION_MESSAGE)


    }
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

fun getSourceFolder(selectedPhotos: List<File>): File {
    return selectedPhotos[0].parentFile
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
        mainApp()
    }
}

@Composable
@Preview
fun mainApp() {
    var currentScreen by remember { mutableStateOf("main") }

    MaterialTheme {
        when (currentScreen) {
            "main" -> mainScreen(
                onNavigateToOptimizer = { currentScreen = "optimizer" },
                onNavigateToFolderConfig = { currentScreen = "folderConfig" },
                onNavigateToHtmlInstaller = { currentScreen = "htmlInstaller" }
            )
            "optimizer" -> optimizerScreen(onNavigateBack = { currentScreen = "main" })
            "folderConfig" -> folderConfigScreen(onNavigateBack = { currentScreen = "main" })
            "htmlInstaller" -> htmlTagInstallerScreen(onNavigateBack = { currentScreen = "main" })
            
        }
    }
}

@Composable
fun mainScreen(
    onNavigateToOptimizer: () -> Unit,
    onNavigateToFolderConfig: () -> Unit,
    onNavigateToHtmlInstaller: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Text(
            text = "Главный экран",
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 32.dp),
            color = Color.Black,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Button(onClick = onNavigateToOptimizer) {
                Text("Перейти к оптимизации фотографий")
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onNavigateToFolderConfig) {
                Text("Конфигурация папок")
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onNavigateToHtmlInstaller) {
                Text("Установщик HTML-тегов")
            }
            
        }
    }
}

@Composable
fun optimizerScreen(onNavigateBack: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.Start
            ) {
                Button(onClick = onNavigateBack) {
                    Text("Главный экран")
                }
            }
            optimizePhoto()
        }
    }
}

@Composable
fun folderConfigScreen(onNavigateBack: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.Start
        ) {
            Button(onClick = onNavigateBack) {
                Text("Главный экран")
            }
        }
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Button(
                onClick = {
                    val selectedPhotos = chooseImages()
                    
                    chooseFolderToConfig(selectedPhotos)
                },
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
            ) {
                Text("Настроить конфигурацию и распределение")
            }
        }
    }
}

@Composable
fun htmlTagInstallerScreen(onNavigateBack: () -> Unit) {
    var errorMessage by remember { mutableStateOf<String?>(null) }
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.Start
        ) {
            Button(onClick = onNavigateBack) {
                Text("Главный экран")
            }
        }
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Button(
                onClick = {
                    val selectedDOCX = chooseDOCX()
                    errorMessage = addIndentationToDocx(
                        selectedDOCX.absolutePath,
                        selectedDOCX.parent + "\\" + selectedDOCX.nameWithoutExtension + "_IM_HTML.docx"
                    )
                },
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
            ) {
                Text("Добавить теги в дневник")
            }
            errorMessage?.let { error ->
                errorDialog(errorMessage = error) {
                    errorMessage = null
                }
            }
        }
    }
}

fun addIndentationToDocx(filePath: String, outputFilePath: String): String? {
    try {
        FileInputStream(filePath).use { fis ->
            val document = XWPFDocument(fis)

            var lastTextParagraph: XWPFParagraph? = null 

            document.paragraphs.forEachIndexed { index, paragraph ->
                val text = paragraph.text
                if (text.isNotBlank()) {
                    lastTextParagraph = paragraph 
                    if (index >= 2) {  
                        val run = paragraph.insertNewRun(0)

                        run.setText("<p>")
                    }
                    if (index >= 2) {
                        paragraph.createRun().apply {
                            setText("</p>\n")
                        }
                    }
                }
            }

            lastTextParagraph?.createRun()?.apply {
                setText("<br>")
            }

            FileOutputStream(outputFilePath).use { fos ->
                document.write(fos)
            }
        }
    } catch (e: IOException) {
        return e.message
    }
    return null
}