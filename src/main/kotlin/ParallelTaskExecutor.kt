import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

val baseScrapeCacheDir = File("scrape-cache")

suspend fun <T, R> List<T>.executeTask(
    taskIdentifier: String,
    threadCount: Int = Runtime.getRuntime().availableProcessors(),
    progressBarWidth: Int = 50,
    taskProcessor: suspend (T) -> R
): List<TaskResult<T, R>> = coroutineScope {
    val tasks = this@executeTask
    Executors.newFixedThreadPool(threadCount).asCoroutineDispatcher().use { dispatcher ->
        val startTime = System.currentTimeMillis()
        val totalTasks = tasks.size
        val header = "Task: $taskIdentifier | Count: $totalTasks | Threads: $threadCount"

        val progressChannel = Channel<Int>(Channel.BUFFERED)
        val successCounter = AtomicInteger(0)
        val errorCounter = AtomicInteger(0)
        // Launch progress tracking coroutine if needed
        val progressJob = launch {
            for (update in progressChannel) {
                val success = successCounter.get()
                val error = errorCounter.get()
                val completed = success + error
                val percent = completed * 100 / totalTasks
                val completeWidth = progressBarWidth * percent / 100
                val remainingWidth = progressBarWidth - completeWidth
                val bar = "[" + "=".repeat(completeWidth) + " ".repeat(remainingWidth) + "]"

                print("\r$header | Progress: $bar $percent% | Success: $success | Errors: $error")
            }
        }


        // Execute tasks
        val results = withContext(dispatcher) {
            tasks.map { task ->
                async {
                    try {
                        val result = taskProcessor(task)
                        successCounter.incrementAndGet()
                        progressChannel.send(1)
                        TaskResult.Success(task, result)
                    } catch (e: Exception) {
                        errorCounter.incrementAndGet()
                        progressChannel.send(1)
                        TaskResult.Failure(task, e)
                    }
                }
            }.awaitAll()
        }
        progressChannel.close()
        progressJob.join()

        val endTime = System.currentTimeMillis()

        val successful = results.count { it is TaskResult.Success }
        val failed = results.count { it is TaskResult.Failure }
        println(
            "\r$header" +
                    " | Completed in ${(endTime - startTime) / 1000.0} seconds" +
                    " | Results: \u001B[32m$successful successful\u001B[0m, \u001B[31m$failed failed\u001B[0m"
        )

        return@coroutineScope results
    }
}

suspend fun <T, R1, R2> List<TaskResult<T, R1>>.executeTask(
    taskIdentifier: String,
    threadCount: Int = Runtime.getRuntime().availableProcessors(),
    progressBarWidth: Int = 50,
    taskProcessor: suspend (T, R1) -> R2
): List<TaskResult<T, R2>> = this
    .executeTask(taskIdentifier, threadCount, progressBarWidth) { result ->
        when (result) {
            is TaskResult.Success -> taskProcessor.invoke(result.task, result.result)
            is TaskResult.Failure -> throw result.error
        }
    }.mapResults()

fun <T, R1, R2> List<TaskResult<TaskResult<T, R1>, R2>>.mapResults(): List<TaskResult<T, R2>> =
    this.map { result: TaskResult<TaskResult<T, R1>, R2> ->
        when (result) {
            is TaskResult.Success -> when (result.task) {
                is TaskResult.Success -> TaskResult.Success(result.task.task, result.result)
                is TaskResult.Failure -> TaskResult.Failure(result.task.task, result.task.error)
            }

            is TaskResult.Failure -> when (result.task) {
                is TaskResult.Success -> TaskResult.Failure(result.task.task, result.error)
                is TaskResult.Failure -> TaskResult.Failure(result.task.task, result.task.error)
            }
        }
    }

suspend fun <T> List<T>.executeScrapeTask(
    taskIdentifier: String,
    threadCount: Int = Runtime.getRuntime().availableProcessors(),
    progressBarWidth: Int = 50,
    urlFileProcessor: suspend (T) -> Pair<String, String>
): List<TaskResult<T, File>> =
    executeTask(taskIdentifier, threadCount, progressBarWidth) { task ->
        urlFileProcessor(task).let { (url, fileName) ->
            File(baseScrapeCacheDir, fileName).also { file ->
                if (!file.exists()) {
                    file.parentFile.mkdirs()
                    file.writeBytes(URI(url).toURL().readBytes())
                }
            }
        }
    }

suspend fun <T, R> List<TaskResult<T, R>>.executeScrapeTask(
    taskIdentifier: String,
    threadCount: Int = Runtime.getRuntime().availableProcessors(),
    progressBarWidth: Int = 50,
    urlFileProcessor: suspend (T, R) -> Pair<String, String>
) = this
    .executeScrapeTask(taskIdentifier, threadCount, progressBarWidth) { result ->
        when (result) {
            is TaskResult.Success -> urlFileProcessor.invoke(result.task, result.result)
            is TaskResult.Failure -> throw result.error
        }
    }.mapResults()

fun <T, R> List<TaskResult<T, out Iterable<R>>>.flattenTaskResults(): List<TaskResult<T, R>> = flatMap { taskResult ->
    when (taskResult) {
        is TaskResult.Success -> taskResult.result.map { result -> TaskResult.Success(taskResult.task, result) }
        is TaskResult.Failure -> listOf(TaskResult.Failure(taskResult.task, taskResult.error))
    }
}

fun <T, R1, R2> List<TaskResult<T, R1>>.mapResults(transform: (R1) -> R2): List<TaskResult<T, R2>> = map {
    when (it) {
        is TaskResult.Success -> TaskResult.Success(it.task, transform(it.result))
        is TaskResult.Failure -> TaskResult.Failure(it.task, it.error)
    }
}

fun <T, R> List<TaskResult<T, R>>.filterSuccessful() =
    this.mapNotNull { taskResult ->
        when (taskResult) {
            is TaskResult.Success -> taskResult
            is TaskResult.Failure -> null.also {
                System.err.print("Task failed: ${taskResult.task} - Error: ")
                taskResult.error.printStackTrace()
            }
        }
    }

fun <T, R> List<TaskResult<T, R>>.mapToResult() = filterSuccessful().map { it.result }

sealed class TaskResult<T, R>(open val task: T) {
    data class Success<T, R>(override val task: T, val result: R) : TaskResult<T, R>(task)

    data class Failure<T, R>(override val task: T, val error: Exception) : TaskResult<T, R>(task)
}
