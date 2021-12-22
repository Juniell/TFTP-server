import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.file.Paths
import kotlin.system.exitProcess

class TFTPServer(port: Int = 8888, directoryName: String = "TFTPFiles") {
    private val server: DatagramSocket = DatagramSocket(port)   // сокет сервера
    private val workDir: String

    init {
        // Проверка наличия рабочей директории или её создание
        val path = Paths.get("").toAbsolutePath().toString() + File.separator + directoryName
        val file = File(path)

        if (!file.exists() || !file.isDirectory) {
            if (file.mkdir())
                println("Была создана рабочая директория $path")
            else {
                println("Не удалось создать рабочую директорию $path")
                exitProcess(0)
            }
        }
        workDir = path
        waitRequest()
    }

    private fun waitRequest() {
        // 516?? 512???
        val getBuffer = ByteArray(516)     // буфер для хранения получаемых данных
        // экземпляр UDP-пакета для хранения клиентских данных с использованием буфера для полученных данных
        val inputPacket = DatagramPacket(getBuffer, getBuffer.size)
        println("Ожидаю запрос от клиента")
        server.receive(inputPacket) // данные от клиента сохранить в inputPacket

        println("Клиент прислал: ${inputPacket.data}")

        val packet = processingPacket(inputPacket.data)

        when (packet.opcode) {
            Opcode.RRQ -> sendFile()
            Opcode.WRQ -> getFile(inputPacket.address, inputPacket.port, packet.fileName)
        }
    }


    private fun processingDataPacket(data: ByteArray, lastDataIndex: Int): DataPacket {
        val opcode = getOpcode(data.slice(0..1))
        val blockNum = data.slice(2..3).toInt()
        val dataB = data.slice(4 until lastDataIndex)

        return DataPacket(opcode, blockNum, dataB)
    }

    private fun processingPacket(data: ByteArray): TFTPPacket {
        val opcodeB = data.slice(0..1)
        val opcode = getOpcode(opcodeB)

        val fileNameB = mutableListOf<Byte>()
        var byte = data[2]
        var i = 3

        while (byte != 0.toByte()) {
            fileNameB.add(byte)
            byte = data[i]
            i++
        }

        val fileName = fileNameB.toByteArray().toString(Charsets.US_ASCII)
        return TFTPPacket(opcode, fileName, fileNameB)
    }

    private fun getOpcode(opcodeB: List<Byte>): Opcode {
        if (opcodeB.size != 2)
            throw IllegalArgumentException("Размер поданного opcodeB не равен 2 байт")

        // Получаем значение в Int todo: можно просто брать второй байт сразу и toInt???
        return when ((opcodeB[0].toUByte().toInt() shl 8) or (opcodeB[1].toUByte().toInt())) {
            1 -> Opcode.RRQ
            2 -> Opcode.WRQ
            3 -> Opcode.DATA
            4 -> Opcode.ACK
            else -> Opcode.ERROR
        }
    }

    private fun getFile(address: InetAddress, port: Int, fileName: String) {     // для WRQ
        val path = workDir + File.separator + fileName
        val file = File(path)

        if (file.exists())
            TODO("Добавить отправку ошибки: Такой файл уже существует")

        val fileBytes = mutableListOf<Byte>()
        // Подтверждаем, что готовы получать
        sendACK(address, port, 0)

        val getBuffer = ByteArray(516)
        var inputPacket = DatagramPacket(getBuffer, getBuffer.size)
        server.receive(inputPacket)

        while (true) {
            val packet = processingDataPacket(inputPacket.data, inputPacket.length)

            if (packet.opcode != Opcode.DATA)
                TODO("Что-то сделать")

            if (inputPacket.port != port)
                TODO("Добавить отправку сообщения об ошибке идентификации (не совпадают TDI)")


            fileBytes.addAll(packet.dataB)              // Записываем байты файла
            sendACK(address, port, packet.blockNum)     // Подтверждаем получение


            // Последний пакет будет меньше 516 байт, на нём выходим
            if (inputPacket.length != 516)
                break

            // Ждём новый пакет
            val buffer = ByteArray(516)
            inputPacket = DatagramPacket(buffer, buffer.size)
            server.receive(inputPacket)
        }

        // Создаём файл и записываем данные в него
        file.createNewFile()
        file.writeBytes(fileBytes.toByteArray())
        println("Скачен файл: ${file.path}")
    }


    private fun sendFile() {    // для RRQ

    }

    private fun sendACK(address: InetAddress, port: Int, blockNum: Int) {
        val numB = if (blockNum <= 255)
            byteArrayOf(0.toByte(), blockNum.toByte())
        else
            byteArrayOf((blockNum shr 8).toByte(), blockNum.toByte())

        val sendBuffer = byteArrayOf(0.toByte(), Opcode.ACK.opcode.toByte(), numB[0], numB[1])
        val outputPacket = DatagramPacket(sendBuffer, sendBuffer.size, address, port)

        println("Отправлен пакет ACK: ${outputPacket.data}")
        server.send(outputPacket)
    }


    enum class Opcode(val opcode: Int) {
        RRQ(1),     // Read request
        WRQ(2),     // Write request
        DATA(3),    // Data
        ACK(4),     // Acknowledgment
        ERROR(5)    // Error
    }

    data class TFTPPacket(
        val opcode: Opcode,
        val fileName: String,
        val fileNameB: List<Byte>
    )

    data class DataPacket(
        val opcode: Opcode,
        val blockNum: Int,
        val dataB: List<Byte>
    )

//    Пакет
//    2 bytes     string    1 byte     string   1 byte
//    ------------------------------------------------
//    | Opcode |  Filename  |   0  |    Mode    |   0  |
//    ------------------------------------------------


//    Пакет данных
//    2 bytes     2 bytes      n bytes
//    ----------------------------------
//    | Opcode |   Block #  |   Data     |
//    ----------------------------------


//    Пакет ACK
//    2 bytes     2 bytes
//    ---------------------
//    | Opcode |   Block #  |
//    ---------------------

}

fun List<Byte>.toInt(): Int {
    var result = 0
    var shift = 0
    this.reversed().forEach {
        result += it.toUByte().toInt() shl shift
        shift += 8
    }
    return result
}