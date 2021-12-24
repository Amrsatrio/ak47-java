package com.tb24.discordbot.managers

import com.google.gson.JsonObject
import com.tb24.discordbot.util.exec
import com.tb24.fn.EpicApi
import com.tb24.fn.model.party.*
import com.tb24.fn.util.MetaStringMap
import com.tb24.fn.util.getString
import retrofit2.Response
import java.util.*

class PartyManager(private val api: EpicApi) {
	var partyInfo: FPartyInfo? = null

	init {
		//api.eventBus.register(this)
	}

	fun createParty(bEmbedded: Boolean): Response<FPartyInfo> =
		api.partyService.createParty("Fortnite", FPartyInfo().apply {
			config = FPartyConfigInfo().apply {
				join_confirmation = false
				joinability = FPartyConfigInfo.EPartyJoinability.INVITE_AND_FORMER
				max_size = 16
			}
			join_info = PartyJoinInfo().apply {
				connection = FConnectionInfo().apply {
					//id = app.xmppConnectionManager.connection.user.asUnescapedString()
					meta = MetaStringMap().apply {
						put("urn:epic:conn:platform", xmppOssPlatform)
						put("urn:epic:conn:type", (if (bEmbedded) FPartyInfo.EPartyConnectionType.embedded else FPartyInfo.EPartyConnectionType.game).toString())
					}
					offline_ttl = 1800
				}
				meta = MetaStringMap().apply {
					put("urn:epic:member:dn", api.currentLoggedIn.displayName)
				}
			}
			meta = MetaStringMap().apply {
				put("urn:epic:cfg:party-type-id", FPartyConfigInfo.EPartyType.DEFAULT.toString().toLowerCase(Locale.ENGLISH))
				put("urn:epic:cfg:build-id", "1:3:")
				put("urn:epic:cfg:join-request-action", (if (bEmbedded) FPartyInfo.EPartyJoinRequestAction.AutoApprove else FPartyInfo.EPartyJoinRequestAction.Manual).toString())
				put("urn:epic:cfg:presence-perm", (if (bEmbedded) FPartyInfo.EPartyPerm.Anyone else FPartyInfo.EPartyPerm.Noone).toString())
				put("urn:epic:cfg:invite-perm", (if (bEmbedded) FPartyInfo.EPartyPerm.Anyone else FPartyInfo.EPartyPerm.Noone).toString())
				put("urn:epic:cfg:chat-enabled", true)
				if (bEmbedded) {
					put("urn:epic:cfg:accepting-members", true)
				} else {
					put("urn:epic:cfg:accepting-members", false)
					put("urn:epic:cfg:not-accepting-members-reason", 0)
				}
			}
		}).exec().also {
			this.partyInfo = it.body()
		}

	fun fetchParty() {
		api.partyService.getUserSummary("Fortnite", api.currentLoggedIn.id).exec().also {
			val body = it.body()!!
			this.partyInfo = body.current.firstOrNull()
		}
	}

	fun updateParty(payload: PartyUpdatePayload = PartyUpdatePayload()): Response<Void> =
		api.partyService.updateParty("Fortnite", partyInfo!!.id, payload.apply {
			revision = partyInfo!!.revision
		}).exec()

	fun updateMemberState(accountId: String, payload: MetaPatchPayload = MetaPatchPayload()): Response<Void> =
		api.partyService.updateMemberState("Fortnite", partyInfo!!.id, accountId, payload.apply {
			revision = partyInfo!!.revision
		}).exec()

	fun promote(accountId: String): Response<Void> =
		api.partyService.transferLeadershipToMember("Fortnite", partyInfo!!.id, accountId).exec()

	fun kick(accountId: String): Response<Void> =
		api.partyService.deleteMember("Fortnite", partyInfo!!.id, accountId).exec()

	fun invite(accountId: String): Response<Void> =
		api.partyService.sendInvite("Fortnite", partyInfo!!.id, accountId, true, MetaStringMap().apply {
			putAll(partyInfo!!.join_info.connection.meta)
			putAll(partyInfo!!.join_info.meta)
			put("urn:epic:cfg:build-id", partyInfo!!.meta.getString("urn:epic:cfg:build-id", "1:3:"))
			put("urn:epic:invite:platformdata", "")
		}).exec()

	fun ping(accountId: String): Response<FPingInfo> =
		api.partyService.createPing("Fortnite", accountId, accountId, MetaStringMap().apply {
			put("urn:epic:invite:platformdata", "")
		}).exec()

	/*@Subscribe
	fun onXmppMessageReceived(event: XmppMessageEvent) {
		val msgBody = event.body
		when (event.type) {
			"com.epicgames.social.party.notification.v0.INITIAL_INVITE" -> {
			}
			"com.epicgames.social.party.notification.v0.MEMBER_REQUIRE_CONFIRMATION" -> {
			}
			"com.epicgames.social.party.notification.v0.INVITE_EXPIRED" -> {
			}
			"com.epicgames.social.party.notification.v0.INVITE_CANCELLED" -> {
			}
			"com.epicgames.social.party.notification.v0.INVITE_DECLINED" -> {
			}
			"com.epicgames.social.party.notification.v0.PING" -> {
			}
			"com.epicgames.social.party.notification.v0.PARTY_UPDATED" -> {
			}
			"com.epicgames.social.party.notification.v0.PARTY_DISBANDED" -> {
			}
			"com.epicgames.social.party.notification.v0.MEMBER_JOINED" -> {
				notiService.notify(
					0, XmppConnectionManager.createNotificationBuilder(app, "party")
						.setContentTitle(app.format(L10N.PartyMemberCreated))
						.setContentText(app.format(L10N.PartyMemberCreatedDetails, msgBody.getString("account_dn", app.getString(R.string.unknown))))
						.build()
				)
				if (!checkParty(msgBody)) {
					return
				}
				partyInfo!!.members.add(FMemberInfo().apply {
					account_id = msgBody.getString("account_id")
					meta = MetaStringMap(msgBody.getAsJsonObject("member_state_updated"))
					connections = arrayOf()
					revision = msgBody.getInt("revision")
					updated_at = msgBody.getDateISO("updated_at")
					joined_at = msgBody.getDateISO("joined_at")
					role = FMemberInfo.EPartyMemberRole.MEMBER
				})
			}
			"com.epicgames.social.party.notification.v0.MEMBER_CONNECTED" -> {
			}
			"com.epicgames.social.party.notification.v0.MEMBER_DISCONNECTED" -> {
			}
			"com.epicgames.social.party.notification.v0.MEMBER_REJECTED" -> {
			}
			"com.epicgames.social.party.notification.v0.MEMBER_STATE_UPDATED" -> {
			}
			"com.epicgames.social.party.notification.v0.MEMBER_LEFT" -> {
			}
			"com.epicgames.social.party.notification.v0.MEMBER_KICKED" -> {
			}
			"com.epicgames.social.party.notification.v0.MEMBER_EXPIRED" -> {
			}
			"com.epicgames.social.party.notification.v0.MEMBER_NEW_CAPTAIN" -> {
			}
			"com.epicgames.social.party.notification.v0.INVITE_LEADERSHIP_TRANSFER" -> {
			}
		}
	}*/

	fun checkParty(msgBody: JsonObject) = partyInfo != null && partyInfo!!.id == msgBody.getString("party_id")

	fun getMemberOrNull(accountId: String) = partyInfo?.members?.firstOrNull { it.account_id == accountId }

	fun getMember(accountId: String) = getMemberOrNull(accountId) ?: throw IllegalArgumentException("Member $accountId is not in the party")

	val xmppOssPlatform: String
		get() = "WIN" //get() = app.xmppConnectionManager.connection.user.resourcepart.toString().split(":".toRegex()).toTypedArray()[2]

	val accountId: String
		get() = api.currentLoggedIn.id //get() = app.xmppConnectionManager.connection.user.localpart.asUnescapedString()
}