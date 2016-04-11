package client

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.routing.RoundRobinPool
import com.virtuslab.akkaworkshop.PasswordsDistributor._

class RequesterActor(remote: ActorRef) extends Actor with ActorLogging {

  import RequesterActor._

  var token = ""
  val name = "Basics"

  val workersNumber = 10
  val workers = context.actorOf(RoundRobinPool(workersNumber).props(Worker.props))

  override def preStart() = {
    remote ! registerMessage(name)
  }

  override def receive: Receive = starting

  def starting: Receive = {
    case Registered(newToken) =>
      log.info(s"Registered with token $newToken")
      token = newToken
      for(_ <- 0 until workersNumber) remote ! sendPasswordMessage(token)
      context.become(working)
  }

  def working: Receive = {
    case encryptedPassword : EncryptedPassword =>
      workers ! encryptedPassword

    case ValidateDecodedPassword(_, encrypted, decrypted) =>
      remote ! ValidateDecodedPassword(token, encrypted, decrypted)

    case PasswordCorrect(decryptedPassword) =>
      log.info(s"Password $decryptedPassword was decrypted successfully")
      remote ! sendPasswordMessage(token)

    case PasswordIncorrect(decryptedPassword) =>
      log.error(s"Password $decryptedPassword was not decrypted correctly")
      remote ! sendPasswordMessage(token)
  }

}

object RequesterActor {

  def props(remote: ActorRef) = Props(classOf[RequesterActor], remote)

  // messages needed to communicate with the server
  def registerMessage(name: String) = Register(name)

  def validatePasswordMessage(token: Token,
                              encryptedPassword: String,
                              decryptedPassword: String) =
    ValidateDecodedPassword(token, encryptedPassword, decryptedPassword)

  def sendPasswordMessage(token: Token) = SendMeEncryptedPassword(token)


}
