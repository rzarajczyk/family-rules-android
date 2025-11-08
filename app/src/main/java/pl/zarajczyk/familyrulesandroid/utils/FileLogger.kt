package pl.zarajczyk.familyrulesandroid.utils

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Logs application events to files on device storage.
 * Captures DEBUG, INFO, WARN, and ERROR logs.
 * Separate from CrashLogger which only handles unhandled exceptions.
 */
object FileLogger {
    private const val TAG = "FileLogger"
    private const val LOG_DIR = "app_logs"
    private const val MAX_LOG_FILE_SIZE = 5 * 1024 * 1024 // 5 MB per file
    private const val MAX_LOG_FILES = 5 // Keep only last 5 log files
    
    private var logFile: File? = null
    private val lock = ReentrantReadWriteLock()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val filenameDateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
    
    private var appContext: Context? = null
    private var initialized = false
    
    /**
     * Initialize the file logger. Must be called before using.
     */
    fun init(appContext: Context) {
        this.appContext = appContext.applicationContext // Use application context to avoid leaks
        initialized = true
        rotateLogFileIfNeeded()
        d(TAG, "FileLogger initialized")
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
     * Writes a log entry to the current log file
     */
    private fun writeLog(level: String, tag: String, message: String, throwable: Throwable?) {
        if (!initialized) {
            return
        }
        
        try {
            lock.write {
                rotateLogFileIfNeeded()
                
                val currentFile = logFile ?: return
                
                val timestamp = dateFormat.format(Date())
                val logEntry = StringBuilder()
                logEntry.append("$timestamp [$level] $tag: $message\n")
                
                // Add throwable if present
                if (throwable != null) {
                    logEntry.append("  Exception: ${throwable.javaClass.name}: ${throwable.message}\n")
                    throwable.stackTrace.take(10).forEach { element ->
                        logEntry.append("    at $element\n")
                    }
                    if (throwable.stackTrace.size > 10) {
                        logEntry.append("    ... ${throwable.stackTrace.size - 10} more\n")
                    }
                }
                
                currentFile.appendText(logEntry.toString())
            }
        } catch (e: Exception) {
            // Avoid infinite loop - just log to logcat
            Log.e(TAG, "Failed to write to log file", e)
        }
    }
    
    /**
     * Rotates the log file if it exceeds the max size
     */
    private fun rotateLogFileIfNeeded() {
        try {
            val context = appContext ?: return
            val logDir = File(context.filesDir, LOG_DIR)
            if (!logDir.exists()) {
                logDir.mkdirs()
            }
            
            // Check if current log file needs rotation
            val currentFile = logFile
            if (currentFile == null || !currentFile.exists() || currentFile.length() > MAX_LOG_FILE_SIZE) {
                // Create new log file
                val timestamp = filenameDateFormat.format(Date())
                logFile = File(logDir, "app_log_$timestamp.log")
                
                // Write header
                logFile?.writeText(buildLogHeader())
                
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
        header.append("FAMILY RULES - APPLICATION LOG\n")
        header.append("=" .repeat(80) + "\n")
        header.append("Started: ${dateFormat.format(Date())}\n")
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
                file.isFile && file.name.startsWith("app_log_") && file.name.endsWith(".log")
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
     * Returns a list of all log files
     */
    fun getLogFiles(context: Context): List<File> {
        val logDir = File(context.filesDir, LOG_DIR)
        if (!logDir.exists()) {
            return emptyList()
        }
        
        val logFiles = logDir.listFiles { file ->
            file.isFile && file.name.startsWith("app_log_") && file.name.endsWith(".log")
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
                logFile = null
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear logs", e)
            false
        }
    }
}
