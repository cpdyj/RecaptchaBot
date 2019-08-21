import api.func.restrictChatMember
import api.func.sendMessage
import api.type.ChatPermissions
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.vertx.core.Vertx
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.impl.ConcurrentHashSet
import io.vertx.core.json.Json
import io.vertx.core.net.ProxyOptions
import io.vertx.core.net.ProxyType
import io.vertx.ext.web.client.WebClientOptions
import java.util.*
import java.util.concurrent.ConcurrentHashMap

val userSet = ConcurrentHashSet<User>()
val keyMap = ConcurrentHashMap<String, User>()

var botServer: BotServer? = null
fun main() {
    val botKey = System.getenv("RECAPTCHA_BOT_API_KEY") ?: ""
    Json.mapper.registerKotlinModule().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    val vertx = Vertx.vertx()

    // create http server for recaptcha verify request.
    val server = vertx.createHttpServer(HttpServerOptions().setPort(8000))
    server.requestHandler {
        val token = it.getParam("token")
        val key = it.getParam("key")
        val user = keyMap[key]
        if (user != null) {
            if (verifyToken(token)) {
                unlockUser(user)
            }
        }
    }

    // bot service.
    botServer = BotServerImpl(
        vertx,
        botKey,
        WebClientOptions().setProxyOptions(ProxyOptions().setType(ProxyType.SOCKS5).setHost("127.0.0.1").setPort(17654))
    )
    botServer!!.registerNewMessageHandler { msg ->
        //println(msg)
        msg.newChatMembers?.forEach {
            if (it.isBot) {
                // TODO -> kick
            } else {
                lockUser(it, msg.chat.id.toString())
            }
        }
        true
    }
    MsgUpdaterImpl(botServer!!).start()
}

fun lockUser(tgUser: api.type.User, chatId: String) {
    println(chatId)
    val uuid = UUID.randomUUID().toString().replace("-", "")
    val user = User(
        uid = tgUser.id,
        status = State.LOCKED,
        key = uuid,
        chatId = chatId,
        firstName = tgUser.firstName,
        lastName = tgUser.lastName ?: ""
    )
    userSet.add(user)
    keyMap[uuid] = user
    //println("kkkk$chatId")
    botServer!!.getApiContext().restrictChatMember(
        chatId = chatId,
        permissions = ChatPermissions(
            canSendMessages = false,
            canSendMediaMessages = false,
            canSendOtherMessages = false
        ),
        userId = tgUser.id
    ) {
        if (it.succeeded()) {
            botServer!!.getApiContext().sendMessage(
                chatId = chatId,
                text = """
                    *>>>注意<<<*
                    User: [${tgUser.firstName} ${tgUser.lastName}](tg://user?id=${tgUser.id})
                    User id: `${tgUser.id}`
                    Username: `${tgUser.username}`
                    阁下已被自动禁言，请在*300*秒内通过[此链接](https://recaptcha.utau.name/groupJavaer.html?uuid=$uuid)的验证码，否则你将被*请离*！
                    You're restricted, please check the [RECAPTCHA code](https://recaptcha.utau.name/groupJavaer.html?uuid=$uuid) in *300* seconds. Or you will be kicked.
                    #新人锁定
                """.trimIndent(),
                parseMode = "Markdown"
            ) {
                if (it.failed()) {
                    println("bbb")
                    botError(chatId, it.cause(), tgUser.id.toString())
                    println(it.cause())
                }
            }
        } else {
            println("aaa")
            botError(chatId, it.cause(), tgUser.id.toString())
            println(it.cause())
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
    ) {
        if (it.failed()) {
            botError(user.chatId, it.cause(), user.uid.toString())
            println(it.cause())
        }
    }
    botServer!!.getApiContext().sendMessage(
        chatId = user.chatId,
        parseMode = "Markdown",
        text = """
            *欢迎* [${user.firstName} ${user.lastName}](tg://user?id=${user.uid}) 加入本群组，请阅读群介绍和置顶消息：）
            #通过验证
        """.trimIndent()
    ) {
        if (it.failed()) {
            botError(user.chatId, it.cause(), user.uid.toString())
            println(it.cause())
        }
    }
}

fun verifyToken(token: String): Boolean {

    TODO()
}

data class User @JsonCreator constructor(
    val uid: Int,
    val status: State,
    val key: String,
    val chatId: String,
    val firstName: String,
    val lastName: String
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
                """.trimIndent(),
        disableNotification = true
    )
}