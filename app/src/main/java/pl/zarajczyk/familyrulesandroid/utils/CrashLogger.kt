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

object CrashLogger {
    private const val TAG = "CrashLogger"
    private const val CRASH_LOG_DIR = "crash_logs"
    private const val MAX_LOG_FILES = 10 // Keep only last 10 crash logs
    
    /**
     * Logs an unhandled exception to a file in the app's internal storage
     */
    fun logException(context: Context, throwable: Throwable, thread: Thread) {
        try {
            val crashLogDir = File(context.filesDir, CRASH_LOG_DIR)
            if (!crashLogDir.exists()) {
                crashLogDir.mkdirs()
            }
            
            // Create filename with timestamp
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
                .format(Date())
            val logFile = File(crashLogDir, "crash_$timestamp.log")
            
            // Write crash information to file
            logFile.writeText(buildCrashReport(throwable, thread))
            
            Log.e(TAG, "Crash logged to: ${logFile.absolutePath}")
            
            // Clean up old log files
            cleanupOldLogs(crashLogDir)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log crash", e)
        }
    }
    
    /**
     * Builds a detailed crash report
     */
    private fun buildCrashReport(throwable: Throwable, thread: Thread): String {
        val report = StringBuilder()
        
        // Timestamp
        report.append("========================================\n")
        report.append("CRASH REPORT\n")
        report.append("========================================\n")
        report.append("Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
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
        
        // Stack trace
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
        
        report.append("========================================\n")
        
        return report.toString()
    }
    
    /**
     * Removes old log files, keeping only the most recent ones
     */
    private fun cleanupOldLogs(crashLogDir: File) {
        try {
            val logFiles = crashLogDir.listFiles { file ->
                file.isFile && file.name.startsWith("crash_") && file.name.endsWith(".log")
            } ?: return
            
            if (logFiles.size > MAX_LOG_FILES) {
                // Sort by last modified time (oldest first)
                val sortedFiles = logFiles.sortedBy { it.lastModified() }
                
                // Delete oldest files
                val filesToDelete = sortedFiles.take(logFiles.size - MAX_LOG_FILES)
                filesToDelete.forEach { file ->
                    if (file.delete()) {
                        Log.d(TAG, "Deleted old crash log: ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup old logs", e)
        }
    }
    
    /**
     * Returns a list of all crash log files
     */
    fun getCrashLogFiles(context: Context): List<File> {
        val crashLogDir = File(context.filesDir, CRASH_LOG_DIR)
        if (!crashLogDir.exists()) {
            return emptyList()
        }
        
        val logFiles = crashLogDir.listFiles { file ->
            file.isFile && file.name.startsWith("crash_") && file.name.endsWith(".log")
        } ?: return emptyList()
        
        // Return sorted by most recent first
        return logFiles.sortedByDescending { it.lastModified() }
    }
    
    /**
     * Reads the content of a crash log file
     */
    fun readCrashLog(file: File): String {
        return try {
            file.readText()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read crash log", e)
            "Error reading crash log: ${e.message}"
        }
    }
    
    /**
     * Deletes all crash log files
     */
    fun clearAllCrashLogs(context: Context): Boolean {
        return try {
            val crashLogDir = File(context.filesDir, CRASH_LOG_DIR)
            if (crashLogDir.exists()) {
                crashLogDir.deleteRecursively()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear crash logs", e)
            false
        }
    }
    
    /**
     * Creates a combined crash log file for sharing/export
     * Returns null if there are no crash logs
     */
    fun exportAllCrashLogs(context: Context): File? {
        return try {
            val crashLogs = getCrashLogFiles(context)
            if (crashLogs.isEmpty()) {
                return null
            }
            
            // Create a temporary file in cache directory for sharing
            val exportFile = File(context.cacheDir, "crash_logs_export.txt")
            
            exportFile.bufferedWriter().use { writer ->
                writer.write("FAMILY RULES - CRASH LOGS EXPORT\n")
                writer.write("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
                writer.write("Total crash logs: ${crashLogs.size}\n")
                writer.write("=" .repeat(80) + "\n\n")
                
                crashLogs.forEachIndexed { index, logFile ->
                    writer.write("\n")
                    writer.write("=" .repeat(80) + "\n")
                    writer.write("LOG ${index + 1} of ${crashLogs.size}: ${logFile.name}\n")
                    writer.write("=" .repeat(80) + "\n")
                    writer.write(readCrashLog(logFile))
                    writer.write("\n\n")
                }
            }
            
            Log.d(TAG, "Crash logs exported to: ${exportFile.absolutePath}")
            exportFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export crash logs", e)
            null
        }
    }
}
