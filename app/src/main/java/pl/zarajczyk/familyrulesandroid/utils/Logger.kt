package pl.zarajczyk.familyrulesandroid.utils

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Unified logger that handles both normal application logs and crash logs.
 * All logs are written to daily log files (logs-YYYY-MM-DD.txt).
 * Keeps up to 5 log files.
 */
object Logger {
    private const val TAG = "Logger"
    private const val LOG_DIR = "logs"
    private const val MAX_LOG_FILES = 5 // Keep only last 5 log files
    
    private var currentLogFile: File? = null
    private var currentDate: String? = null
    private val lock = ReentrantReadWriteLock()
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    
    private var appContext: Context? = null
    private var initialized = false
    
    /**
     * Initialize the logger. Must be called before using.
     */
    fun init(appContext: Context) {
        this.appContext = appContext.applicationContext // Use application context to avoid leaks
        initialized = true
        rotateLogFileIfNeeded()
        d(TAG, "Logger initialized")
    }
    
    /**
     * Log a DEBUG message
     */
    fun d(tag: String, message: String) {
        Log.d(tag, message)
        writeLog("D", tag, message, null)
    }
    
    /**
     * Log an INFO message
     */
    fun i(tag: String, message: String) {
        Log.i(tag, message)
        writeLog("I", tag, message, null)
    }
    
    /**
     * Log a WARNING message
     */
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w(tag, message, throwable)
        } else {
            Log.w(tag, message)
        }
        writeLog("W", tag, message, throwable)
    }
    
    /**
     * Log an ERROR message
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
        writeLog("E", tag, message, throwable)
    }
    
    /**
     * Log a crash (unhandled exception) with full details
     */
    fun logCrash(throwable: Throwable, thread: Thread) {
        try {
            lock.write {
                rotateLogFileIfNeeded()
                
                val logFile = currentLogFile ?: return
                
                val crashReport = buildCrashReport(throwable, thread)
                logFile.appendText(crashReport)
            }
        } catch (e: Exception) {
            // Avoid infinite loop - just log to logcat
            Log.e(TAG, "Failed to log crash", e)
        }
    }
    
    /**
     * Writes a log entry to the current log file
     */
    private fun writeLog(level: String, tag: String, message: String, throwable: Throwable?) {
        if (!initialized) {
            return
        }
        
        try {
            lock.write {
                rotateLogFileIfNeeded()
                
                val logFile = currentLogFile ?: return
                
                val timestamp = timestampFormat.format(Date())
                val logEntry = StringBuilder()
                logEntry.append("$timestamp [$level] $tag: $message\n")
                
                // Add full stack trace if exception present
                if (throwable != null) {
                    val stringWriter = StringWriter()
                    val printWriter = PrintWriter(stringWriter)
                    throwable.printStackTrace(printWriter)
                    logEntry.append(stringWriter.toString())
                    logEntry.append("\n")
                }
                
                logFile.appendText(logEntry.toString())
            }
        } catch (e: Exception) {
            // Avoid infinite loop - just log to logcat
            Log.e(TAG, "Failed to write to log file", e)
        }
    }
    
    /**
     * Builds a detailed crash report
     */
    private fun buildCrashReport(throwable: Throwable, thread: Thread): String {
        val report = StringBuilder()
        
        val timestamp = timestampFormat.format(Date())
        
        report.append("\n")
        report.append("=" .repeat(80) + "\n")
        report.append("CRASH REPORT\n")
        report.append("=" .repeat(80) + "\n")
        report.append("Timestamp: $timestamp\n")
        report.append("\n")
        
        // Device information
        report.append("Device Information:\n")
        report.append("  Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        report.append("  Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        report.append("  Build: ${Build.DISPLAY}\n")
        report.append("\n")
        
        // Thread information
        report.append("Thread Information:\n")
        report.append("  Thread Name: ${thread.name}\n")
        report.append("  Thread ID: ${thread.id}\n")
        report.append("\n")
        
        // Exception information
        report.append("Exception Information:\n")
        report.append("  Exception Type: ${throwable.javaClass.name}\n")
        report.append("  Message: ${throwable.message ?: "No message"}\n")
        report.append("\n")
        
        // Full stack trace
        report.append("Stack Trace:\n")
        val stringWriter = StringWriter()
        val printWriter = PrintWriter(stringWriter)
        throwable.printStackTrace(printWriter)
        report.append(stringWriter.toString())
        report.append("\n")
        
        // Caused by (if any)
        var cause = throwable.cause
        while (cause != null) {
            report.append("Caused by: ${cause.javaClass.name}: ${cause.message}\n")
            val causeWriter = StringWriter()
            val causePrintWriter = PrintWriter(causeWriter)
            cause.printStackTrace(causePrintWriter)
            report.append(causeWriter.toString())
            report.append("\n")
            cause = cause.cause
        }
        
        report.append("=" .repeat(80) + "\n\n")
        
        return report.toString()
    }
    
    /**
     * Rotates the log file if we're on a new date
     */
    private fun rotateLogFileIfNeeded() {
        try {
            val context = appContext ?: return
            val logDir = File(context.filesDir, LOG_DIR)
            if (!logDir.exists()) {
                logDir.mkdirs()
            }
            
            val today = dateFormat.format(Date())
            
            // Check if we need a new log file (new day or no file exists)
            if (currentDate != today || currentLogFile == null || !currentLogFile!!.exists()) {
                currentDate = today
                currentLogFile = File(logDir, "logs-$today.txt")
                
                // Write header if this is a new file
                if (!currentLogFile!!.exists() || currentLogFile!!.length() == 0L) {
                    currentLogFile!!.writeText(buildLogHeader())
                }
                
                // Cleanup old files
                cleanupOldLogs(logDir)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate log file", e)
        }
    }
    
    /**
     * Builds a header for new log files
     */
    private fun buildLogHeader(): String {
        val header = StringBuilder()
        header.append("=" .repeat(80) + "\n")
        header.append("FAMILY RULES - LOG FILE\n")
        header.append("=" .repeat(80) + "\n")
        header.append("Date: $currentDate\n")
        header.append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        header.append("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        header.append("=" .repeat(80) + "\n\n")
        return header.toString()
    }
    
    /**
     * Removes old log files, keeping only the most recent ones
     */
    private fun cleanupOldLogs(logDir: File) {
        try {
            val logFiles = logDir.listFiles { file ->
                file.isFile && file.name.startsWith("logs-") && file.name.endsWith(".txt")
            } ?: return
            
            if (logFiles.size > MAX_LOG_FILES) {
                // Sort by last modified time (oldest first)
                val sortedFiles = logFiles.sortedBy { it.lastModified() }
                
                // Delete oldest files
                val filesToDelete = sortedFiles.take(logFiles.size - MAX_LOG_FILES)
                filesToDelete.forEach { file ->
                    if (file.delete()) {
                        Log.d(TAG, "Deleted old log file: ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup old logs", e)
        }
    }
    
    /**
     * Returns a list of all log files, sorted by most recent first
     */
    fun getLogFiles(context: Context): List<File> {
        val logDir = File(context.filesDir, LOG_DIR)
        if (!logDir.exists()) {
            return emptyList()
        }
        
        val logFiles = logDir.listFiles { file ->
            file.isFile && file.name.startsWith("logs-") && file.name.endsWith(".txt")
        } ?: return emptyList()
        
        // Return sorted by most recent first
        return logFiles.sortedByDescending { it.lastModified() }
    }
    
    /**
     * Reads the content of a log file
     */
    fun readLogFile(file: File): String {
        return try {
            lock.read {
                file.readText()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read log file", e)
            "Error reading log file: ${e.message}"
        }
    }
    
    /**
     * Deletes all log files
     */
    fun clearAllLogs(context: Context): Boolean {
        return try {
            lock.write {
                val logDir = File(context.filesDir, LOG_DIR)
                if (logDir.exists()) {
                    logDir.deleteRecursively()
                }
                currentLogFile = null
                currentDate = null
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear logs", e)
            false
        }
    }
    
    /**
     * Exports all log files for sharing.
     * Attempts to export as individual files if possible, otherwise concatenates them.
     * Returns a list of files to share, or null if there are no logs.
     */
    fun exportLogs(context: Context): List<File>? {
        return try {
            val logFiles = getLogFiles(context)
            
            if (logFiles.isEmpty()) {
                return null
            }
            
            // Return all individual log files for sharing
            // Android's share intent supports multiple files
            logFiles
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export logs", e)
            null
        }
    }
    
    /**
     * Creates a single concatenated export file containing all logs
     * This is a fallback if individual file sharing is not possible
     */
    fun exportLogsAsSingleFile(context: Context): File? {
        return try {
            val logFiles = getLogFiles(context)
            
            if (logFiles.isEmpty()) {
                return null
            }
            
            // Create a temporary file in cache directory for sharing
            val exportFile = File(context.cacheDir, "family_rules_logs_export.txt")
            
            exportFile.bufferedWriter().use { writer ->
                writer.write("FAMILY RULES - COMPLETE LOGS EXPORT\n")
                writer.write("Generated: ${timestampFormat.format(Date())}\n")
                writer.write("Total log files: ${logFiles.size}\n")
                writer.write("=" .repeat(80) + "\n\n")
                
                logFiles.forEachIndexed { index, logFile ->
                    writer.write("\n")
                    writer.write("=" .repeat(80) + "\n")
                    writer.write("LOG FILE ${index + 1} of ${logFiles.size}: ${logFile.name}\n")
                    writer.write("=" .repeat(80) + "\n")
                    writer.write(readLogFile(logFile))
                    writer.write("\n\n")
                }
            }
            
            Log.d(TAG, "All logs exported to: ${exportFile.absolutePath}")
            exportFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export logs as single file", e)
            null
        }
    }
}
