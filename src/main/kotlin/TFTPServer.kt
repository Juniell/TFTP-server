import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.file.Paths
import kotlin.system.exitProcess


class TFTPServer(port: Int = 69, directoryName: String = "TFTPFiles", private val log: Boolean = true) {
    private val requestSocket: DatagramSocket = DatagramSocket(port)   // сокет сервера, на который приходят запросы
    private val requestThread: Thread
    private val workDir: String
    private var exit = false

    init {
        // Проверка наличия рабочей директории или её создание
        val path = Paths.get("").toAbsolutePath().toString() + File.separator + directoryName
        val file = File(path)

        if (!file.exists() || !file.isDirectory) {
            printLog("Рабочая директория не найдена")
            if (file.mkdir())
                printLog("Была создана рабочая директория $path")
            else {
                printLog("Не удалось создать рабочую директорию $path")
                exitProcess(0)
            }
        }
        workDir = path
        requestThread = Thread { waitRequest() }.apply { start() }
        printLog("Сервер запущен на порту $port")
        Thread { readConsole() }.start()
    }

    private fun readConsole() {
        val read = readLine()?.trim()
        // Если null -> EOF -> выход сочетанием клавиш
        if (read == null || read.startsWith("--stop")) {
            exit = true
            printLog("Сервер получил команду выключения.")
            requestThread.interrupt()
            printLog("Поток на обработку запросов завершён.")
            exitProcess(0)
        }
    }

    /** Ожидаем запрос от клиента и запускаем поток на его обработку **/
    private fun waitRequest() {
        while (!exit) {
            val getBuffer = ByteArray(516)     // буфер для хранения получаемых данных
            // экземпляр UDP-пакета для хранения клиентских данных с использованием буфера для полученных данных
            val inputPacket = DatagramPacket(getBuffer, getBuffer.size)
            printLog("Ожидаю запрос от клиента")
            requestSocket.receive(inputPacket) //   Сохраняем данные клиента в inputPacket

            try {
                val packet = processingRequestPacket(inputPacket.data)
                printLog("Был получен пакет с opcode = ${packet.opcode.opcode} (${packet.opcode.name})")

                when (packet.opcode) {
                    Opcode.RRQ -> Thread { sendFile(inputPacket.address, inputPacket.port, packet.fileName) }.start()
                    Opcode.WRQ -> Thread { getFile(inputPacket.address, inputPacket.port, packet.fileName) }.start()
                    else -> {}  // Обрабатываются в catch
                }
            } catch (e: IllegalArgumentException) {
                sendError(requestSocket, inputPacket.address, inputPacket.port, ErrorPacket(Error.ILLEGAL_OPERATION))
            }
        }
        printLog("Поток на обработку запросов завершён.")
        Thread.currentThread().interrupt()
    }

    /** Обрабатывает и возвращает пакет на запрос скачивания или отправки файла **/
    private fun processingRequestPacket(data: ByteArray): RequestPacket {
        val opcode = getOpcode(data.slice(0..1))

        if (opcode != Opcode.WRQ && opcode != Opcode.RRQ)
            throw IllegalArgumentException("Неверный opcode")

        val fileNameB = mutableListOf<Byte>()
        var byte = data[2]
        var i = 3

        while (byte != 0.toByte()) {
            fileNameB.add(byte)
            byte = data[i]
            i++
        }

        val fileName = fileNameB.toByteArray().toString(Charsets.US_ASCII)
        return RequestPacket(opcode, fileName)
    }

    /** Обрабатывает и возвращает пакет данных **/
    private fun processingDataPacket(inputPacket: DatagramPacket, socket: DatagramSocket): DataPacket {
        val data = inputPacket.data
        val opcode = getOpcode(data.slice(0..1))

        if (opcode != Opcode.DATA) {
            sendError(requestSocket, inputPacket.address, inputPacket.port, ErrorPacket(Error.ILLEGAL_OPERATION))
            socket.close()
            Thread.currentThread().interrupt()
        }

        val blockNum = data.slice(2..3).toInt()
        val dataB = data.slice(4 until inputPacket.length)

        return DataPacket(blockNum, dataB)
    }

    /** Обрабатывает и возвращает пакет ACK **/
    private fun processingACKPacket(inputPacket: DatagramPacket, socket: DatagramSocket): ACKPacket {
        val data = inputPacket.data
        val opcode = getOpcode(data.slice(0..1))

        if (opcode != Opcode.ACK) {
            sendError(requestSocket, inputPacket.address, inputPacket.port, ErrorPacket(Error.ILLEGAL_OPERATION))
            socket.close()
            Thread.currentThread().interrupt()
        }

        val blockNum = data.slice(2..3).toInt()
        return ACKPacket(blockNum)
    }

    private fun getOpcode(opcodeB: List<Byte>): Opcode {
        if (opcodeB.size != 2)
            throw IllegalArgumentException("Размер поданного opcodeB не равен 2 байт")

        // Получаем значение в Int
        return when (opcodeB[1].toUByte().toInt()) {
            1 -> Opcode.RRQ
            2 -> Opcode.WRQ
            3 -> Opcode.DATA
            4 -> Opcode.ACK
            5 -> Opcode.ERROR
            else -> Opcode.UNKNOWN
        }
    }

    private fun getFile(address: InetAddress, port: Int, fileName: String) {     // для WRQ
        val socket = DatagramSocket()
        val path = workDir + File.separator + fileName
        val file = File(path)

        if (file.exists()) {
            sendError(requestSocket, address, port, ErrorPacket(Error.FILE_ALREADY_EXISTS))
            socket.close()
            Thread.currentThread().interrupt()
        }

        val fileBytes = mutableListOf<Byte>()
        // Подтверждаем, что готовы получать
        sendACK(address, port, 0, socket)

        val getBuffer = ByteArray(516)
        var inputPacket = DatagramPacket(getBuffer, getBuffer.size)
        socket.receive(inputPacket)

        while (!exit) {
            val packet =
                processingDataPacket(inputPacket, socket)

            if (inputPacket.port != port) { // Если получили сообщение от другого клиента на этот порт
                // сообщаем ему об ошибке
                sendError(requestSocket, inputPacket.address, inputPacket.port, ErrorPacket(Error.UNKNOWN_TRANSFER_ID))
                // и ждём дальше сообщения от нашего клиента
                continue
            }

            fileBytes.addAll(packet.dataB)                      // Записываем байты файла
            sendACK(address, port, packet.blockNum, socket)     // Подтверждаем получение

            // Последний пакет будет меньше 516 байт, на нём выходим
            if (inputPacket.length != 516)
                break

            // Ждём новый пакет
            val buffer = ByteArray(516)
            inputPacket = DatagramPacket(buffer, buffer.size)
            socket.receive(inputPacket)
        }

        socket.close()                              // Закрываем сокет

        if (exit) {     // Если вышли из-за остановки сервера
            printLog("Поток на скачивание файла от $address:$port завершён.")
            Thread.currentThread().interrupt()
            return
        }

        file.createNewFile()                        // Создаём файл
        file.writeBytes(fileBytes.toByteArray())    // Записываем данные в файл
        printLog("Скачен файл: ${file.path}")

        Thread.currentThread().interrupt()          // Останавливаем поток
    }

    private fun sendFile(address: InetAddress, port: Int, fileName: String) {    // для RRQ
        val socket = DatagramSocket()

        val path = workDir + File.separator + fileName
        val file = File(path)

        if (!file.exists() || file.isDirectory) {
            sendError(requestSocket, address, port, ErrorPacket(Error.FILE_NOT_FOUND))
            socket.close()
            Thread.currentThread().interrupt()
        }

        var fileBytes = file.readBytes().toList()
        var sizeToSend = fileBytes.size
        var blockNum = 1

        while (sizeToSend != 0 && !exit) {
            val dataToSend = if (sizeToSend >= 512)
                fileBytes.slice(0 until 512)
            else
                fileBytes.slice(0 until sizeToSend)

            val dataBuff = DataPacket(blockNum, dataToSend).toByteArray()
            val outputPacket = DatagramPacket(dataBuff, dataBuff.size, address, port)
            socket.send(outputPacket)
            printLog("Отправлена часть данных: blockNum = $blockNum", false)

            // Ждём подтверждения получения
            val ackAnswer = getACK(socket)

            if (ackAnswer.first != port) { // Если получили сообщение от другого клиента на этот порт
                // сообщаем ему об ошибке
                sendError(requestSocket, address, port, ErrorPacket(Error.UNKNOWN_TRANSFER_ID))
                // и ждём дальше сообщения от нашего клиента
                continue
            }

            if (ackAnswer.second != blockNum) { // Если не совпадает блок
                sendError(requestSocket, address, port, ErrorPacket(Error.NUM_BLOCK_FAILED))
                socket.close()
                Thread.currentThread().interrupt()
            }

            // Если всё ок
            fileBytes = fileBytes.drop(dataToSend.size)
            sizeToSend = fileBytes.size
            blockNum++
        }
        socket.close()                      // Закрываем временный сокет

        if (exit) {     // Если вышли из-за остановки сервера
            printLog("Поток на отправку файла для $address:$port завершён.")
            Thread.currentThread().interrupt()
            return
        }

        printLog("Файл ${file.path} был успешно отправлен")
        Thread.currentThread().interrupt()  // Останавливаем поток
    }

    private fun sendACK(address: InetAddress, port: Int, blockNum: Int, socket: DatagramSocket) {
        val numB = if (blockNum <= 255)
            byteArrayOf(0.toByte(), blockNum.toByte())
        else
            byteArrayOf((blockNum shr 8).toByte(), blockNum.toByte())

        val sendBuffer = byteArrayOf(0.toByte(), Opcode.ACK.opcode.toByte(), numB[0], numB[1])
        val outputPacket = DatagramPacket(sendBuffer, sendBuffer.size, address, port)

        socket.send(outputPacket)
        printLog("Отправлен пакет ACK: ${outputPacket.data}")
    }

    private fun getACK(socket: DatagramSocket): Pair<Int, Int> {  // Возвращает пару <Порт, номер блока>
        val buffer = ByteArray(4)
        val inputPacket = DatagramPacket(buffer, buffer.size)
        socket.receive(inputPacket)

        val ackPacket = processingACKPacket(inputPacket, socket)

        return inputPacket.port to ackPacket.blockNum
    }

    private fun sendError(socket: DatagramSocket, address: InetAddress, port: Int, err: ErrorPacket) {
        val sendBuffer = err.toByteArray()
        val outputPacket = DatagramPacket(sendBuffer, sendBuffer.size, address, port)
        socket.send(outputPacket)
        printLog("Отправлен пакет ERROR клиенту $address:$port: ERROR = ${err.errorCode}. ${err.errorMsg}", true)
    }

    private fun printLog(logMsg: String, highlight: Boolean = true) {
        if (log)
            if (highlight) {
                println("**************** $logMsg ****************")
            } else
                println("\t$logMsg")
    }
}

enum class Opcode(val opcode: Int) {
    RRQ(1),     // Read request
    WRQ(2),     // Write request
    DATA(3),    // Data
    ACK(4),     // Acknowledgment
    ERROR(5),   // Error
    UNKNOWN(-1) // Некорректный opcode
}

enum class Error(val err: Pair<Int, String>) {
    FILE_NOT_FOUND(1 to "File not found."),
    NUM_BLOCK_FAILED(3 to "Disk full or allocation exceeded."),
    ILLEGAL_OPERATION(4 to "Illegal TFTP operation."),
    UNKNOWN_TRANSFER_ID(5 to "Unknown transfer ID."),
    FILE_ALREADY_EXISTS(6 to "File already exists.")
}

data class RequestPacket(
    val opcode: Opcode,
    val fileName: String
)

data class DataPacket(
    val blockNum: Int,
    val dataB: List<Byte>
) {
    fun toByteArray(): ByteArray {
        val opcodeB = byteArrayOf(0.toByte(), Opcode.DATA.opcode.toByte())
        val blockNumB = byteArrayOf((blockNum shr 8).toByte(), blockNum.toByte())
        return opcodeB + blockNumB + dataB
    }
}

data class ACKPacket(
    val blockNum: Int
)

data class ErrorPacket(
    val error: Error
) {
    val errorCode = error.err.first
    val errorMsg = error.err.second

    fun toByteArray(): ByteArray {
        val opcodeB = byteArrayOf(0.toByte(), Opcode.ERROR.opcode.toByte())
        val blockNumB = byteArrayOf(0.toByte(), errorCode.toByte())
        return opcodeB + blockNumB + errorMsg.toByteArray(Charsets.US_ASCII)
    }
}

//    Пакет
//    2 bytes     string    1 byte     string   1 byte
//    -------------------------------------------------
//    | Opcode |  Filename  |   0  |    Mode    |   0  |
//    -------------------------------------------------

//    Пакет данных
//    2 bytes     2 bytes      n bytes
//    -----------------------------------
//    | Opcode |   Block #  |   Data     |
//    -----------------------------------

//    Пакет ACK
//    2 bytes     2 bytes
//    ----------------------
//    | Opcode |   Block #  |
//    ----------------------

//    Пакет ERROR
//    2 bytes   2 bytes      string       1 byte
//    ------------------------------------------
//    | Opcode |  ErrorCode |   ErrMsg   |   0  |
//    ------------------------------------------

fun List<Byte>.toInt(): Int {
    var result = 0
    var shift = 0
    this.reversed().forEach {
        result += it.toUByte().toInt() shl shift
        shift += 8
    }
    return result
}