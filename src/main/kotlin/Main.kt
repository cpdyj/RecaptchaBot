import api.func.*
import api.type.ChatPermissions
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.core.net.ProxyOptions
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.ext.web.multipart.MultipartForm
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.HashMap
import kotlin.math.max

val uuidMap = ConcurrentHashMap<String, User>()
val limitMap = ConcurrentHashMap<Long, Int>()

var botServer: BotServer? = null
var webClient: WebClient? = null
val storageFile = System.getenv("STORAGE_FILE") ?: throw Exception("STORAGE_FILE is not set.")
val botKey = System.getenv("RECAPTCHA_BOT_API_KEY") ?: throw RuntimeException("RECAPTCHA_BOT_API_KEY is not set.")


fun main() {
    Json.mapper.registerKotlinModule().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    val vertx = Vertx.vertx()

    if (File(storageFile).exists()) {
        load()
    }

    // create webClient
    webClient = WebClient.create(
        vertx,
        WebClientOptions().setProxyOptions(ProxyOptions(JsonObject(System.getenv("PROXY_SETTING"))))
    )

    // create http server for recaptcha verify request.
    val server = vertx.createHttpServer(HttpServerOptions().setPort(8000))
    server.requestHandler {
        val token = it.getParam("token")
        val uuid = it.getParam("uuid")
        val user = uuidMap[uuid]
        if (user != null) {
            verifyToken(token).setHandler {
                if (it.result() == true) {
                    unlockUser(user)
                }
            }
        }
        it.response().setStatusCode(302).setStatusMessage("Temporary Redirect")
            .putHeader("Location", "https://recaptcha.utau.name/done.html")
            .end()
        save()
    }

    // bot service.
    botServer = BotServerImpl(
        vertx,
        botKey,
        WebClientOptions().setProxyOptions(ProxyOptions(JsonObject(System.getenv("PROXY_SETTING"))))
    )
    botServer!!.registerNewMessageHandler { msg ->
        //println(msg)

        msg.newChatMembers?.forEach {
            if (it.isBot) {
                // TODO -> kick
            } else {
                lockUser(it, msg.chat.id.toString())
            }
            botServer!!.getApiContext().deleteMessage(msg.chat.id.toString(), msg.messageId)
            save()
        }

        true
    }
    uuidMap.forEach { (uuid, user) ->
        val kd = user.kickDate
        println("""kick user: ${user.firstName}   ${max(kd.time - Date().time, 1)}""")
        vertx.setTimer(max(kd.time - Date().time, 1)) {
            kickUser(uuid, user.chatId)
        }
    }

    MsgUpdaterImpl(botServer!!).start()
}

fun lockUser(tgUser: api.type.User, chatId: String) {
    println(chatId)
    val uuid = UUID.randomUUID().toString().replace("-", "")
    val user = User(
        uid = tgUser.id,
        status = State.LOCKED,
        uuid = uuid,
        chatId = chatId,
        firstName = tgUser.firstName,
        lastName = tgUser.lastName ?: "",
        kickDate = Calendar.getInstance().apply { add(Calendar.MINUTE, limitMap[chatId.toLong()] ?: 10) }.time
    )
    putUser(uuid, user)
    botServer!!.getApiContext().restrictChatMember(
        chatId = chatId,
        permissions = ChatPermissions(
            canSendMessages = false,
            canSendMediaMessages = false,
            canSendOtherMessages = false
        ),
        userId = tgUser.id
    ).compose {
        botServer!!.vertx.setTimer((limitMap[chatId.toLong()] ?: 10) * 60 * 1000L) {
            kickUser(uuid, chatId)
        }
        botServer!!.getApiContext().sendMessage(
            chatId = chatId,
            text = """
                    *>>>注意<<<*
                    User: [${tgUser.firstName} ${tgUser.lastName}](tg://user?id=${tgUser.id})
                    User id: `${tgUser.id}`
                    Username: `${tgUser.username}`
                    阁下已被自动禁言，请在*${limitMap[chatId.toLong()]
                ?: 10}*分钟内通过[此链接](https://recaptcha.utau.name/?uuid=$uuid)的验证码，否则你将被*请离*！
                    You're restricted, please check the [RECAPTCHA code](https://recaptcha.utau.name/?uuid=$uuid) in *300* seconds. Or you will be kicked.
                    #新人锁定
                """.trimIndent(),
            parseMode = "Markdown"
        ).apply {
            botServer!!.vertx.setTimer((limitMap[chatId.toLong()] ?: 10) * 1000L) {
                kickUser(uuid, chatId)
                botServer!!.getApiContext().deleteMessage(chatId, this@apply.result()?.result?.messageId ?: 0)
            }
        }
    }.setHandler {
        if (it.failed()) {
            botError(chatId, it.cause(), user.uid.toString())
        }
    }
}

fun unlockUser(user: User) {
    botServer!!.getApiContext().restrictChatMember(
        chatId = user.chatId,
        userId = user.uid,
        permissions = ChatPermissions(
            canSendOtherMessages = true,
            canSendMediaMessages = true,
            canSendMessages = true,
            canAddWebPagePreviews = true,
            canChangeInfo = true,
            canInviteUsers = true,
            canPinMessages = true,
            canSendPolls = true
        )
    ).compose {
        botServer!!.getApiContext().sendMessage(
            chatId = user.chatId,
            parseMode = "Markdown",
            text = """
            *欢迎* [${user.firstName} ${user.lastName}](tg://user?id=${user.uid}) 加入本群组，请阅读群介绍和置顶消息：）
            #通过验证
        """.trimIndent()
        )
    }.setHandler {
        if (it.failed()) {
            botError(user.chatId, it.cause(), user.uid.toString())
            println(it.cause())
        }
    }
}

fun verifyToken(token: String): Future<Boolean> = Future.future { promise ->
    val form = MultipartForm.create()
    form.attribute("secret", System.getenv("RECAPTCHA_KEY") ?: throw RuntimeException("RECAPTCHA_KEY is not set."))
    form.attribute("response", token)
    webClient!!.postAbs("https://www.google.com/recaptcha/api/siteverify")
        .sendMultipartForm(form) {
            if (it.succeeded()) {
                promise.tryComplete(it.result().bodyAsJsonObject().getBoolean("success"))
            } else {
                promise.tryFail(it.cause())
                println(it.cause())
            }
        }
}

fun kickUser(uuid: String, chatId: String) {
    val u = uuidMap[uuid] ?: return
    delUser(uuid)
    botServer!!.getApiContext().sendMessage(
        chatId = chatId,
        parseMode = "Markdown",
        text = """
            *Kick User:* [${u.firstName} ${u.lastName}](tg://user?id=${u.uid})
            #kick
        """.trimIndent()
    ).compose { result ->
        save()
        botServer!!.vertx.setTimer(20000) {
            if (result?.result?.messageId != null) {
                botServer!!.getApiContext().deleteMessage(
                    chatId = chatId,
                    messageId = result.result.messageId
                )
            }
        }
        botServer!!.getApiContext().kickChatMember(
            chatId = chatId,
            userId = u.uid
        )
    }.compose {
        botServer!!.getApiContext().unbanChatMember(chatId, u.uid)
    }.setHandler {
        if (it.failed()) {
            botError(chatId, it.cause(), u.uid.toString())
        }
    }
}

data class User @JsonCreator constructor(
    val uid: Int,
    val status: State,
    val uuid: String,
    val chatId: String,
    val firstName: String,
    val lastName: String,
    val kickDate: Date
)

enum class State {
    LOCKED, UNLOCK
}

fun botError(chatId: String, exception: Throwable, uid: String) {
    exception.printStackTrace()
    botServer!!.getApiContext().sendMessage(
        chatId = chatId,
        text = """
                    Bot异常： ${exception.message}
                    涉及uid: [$uid](tg://user?id=$uid)
                    #bot异常
                """.trimIndent().replace(botKey, "******"),
        disableNotification = true,
        parseMode = "Markdown"
    )
}

data class Storage(val groups: List<Group>)

data class Group(val chatId: Long, val users: List<User>, val timeLimit: Int)

fun save() {
    val map = HashMap<Long, LinkedList<User>>()
    uuidMap.forEach { (_, user) ->
        val chatId = user.chatId
        map.getOrPut(chatId.toLong(), { LinkedList() }).add(user)
    }
    val list = map.map { (chatId, users) ->
        Group(chatId = chatId, timeLimit = limitMap[chatId] ?: 10, users = users)
    }.toList()
    val a = JsonObject.mapFrom(Storage(list))
    FileWriter("$storageFile.new").apply {
        write(a.encode())
        close()
    }
    File(storageFile).delete()
    File("$storageFile.new").renameTo(File(storageFile))
    println("File saved success.")
}

fun load() {
    val file = FileReader(storageFile)
    val a = JsonObject(file.readText()).mapTo(Storage::class.java)
    file.close()
    a.groups.forEach {
        limitMap[it.chatId] = it.timeLimit
        it.users.forEach {
            uuidMap[it.uuid] = it
        }
    }
}

fun putUser(uuid: String, user: User) = uuidMap.put(uuid, user)

fun delUser(uuid: String) =
    uuidMap.remove(uuid)
