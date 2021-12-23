import org.kohsuke.args4j.CmdLineException
import org.kohsuke.args4j.CmdLineParser
import org.kohsuke.args4j.Option


class Launcher {
    @Option(name = "-p", usage = "port")
    private var port = 69

    @Option(name = "-d", usage = "directoryName")
    private var directoryName = "TFTPFiles"

    @Option(name = "-l", usage = "log")
    private var log = true


    fun launch(args: Array<String>) {
        val parser = CmdLineParser(this)
        try {
            parser.parseArgument(*args)
        } catch (e: CmdLineException) {
            System.err.println(e.message)
            System.err.println("java -jar TFTPServer.jar [-p port] [-d directoryName] [-l log]")
            parser.printUsage(System.err)
        }
        try {
            TFTPServer(port, directoryName, log)
        } catch (e: Exception) {
            System.err.println(e.message)
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            Launcher().launch(args)
        }
    }
}