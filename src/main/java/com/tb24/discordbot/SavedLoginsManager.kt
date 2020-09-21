package com.tb24.discordbot

import com.rethinkdb.RethinkDB.r
import com.rethinkdb.net.Connection
import com.tb24.fn.model.account.DeviceAuth

class SavedLoginsManager(private val conn: Connection) {
	companion object {
		private val GODS = arrayOf("624299014388711455") //require("./Gods.json");
	}

	fun getAll(ticketId: String) =
		r.table("devices")[ticketId].run(conn, Entry::class.java).first()?.devices ?: emptyList()

	fun get(ticketId: String, accountId: String) =
		getAll(ticketId).find { it.accountId == accountId }

	/*async put(ticketId, device) {
		const dbEntry = r.table("devices").get(ticketId).run(conn);
		const devices = dbEntry ? dbEntry.devices || [] : [];

		if (devices.find(it = > it.accountId == device.accountId)){
			return false; // already exists
		}

		devices.push(device);
		const newContents = {
			id:ticketId,
			devices:devices
		};

		if (dbEntry) {
			r.table("devices").update(newContents);
		} else {
			r.table("devices").insert(newContents);
		}

		return true;
	}

	async remove(ticketId, accountId) {
		const dbEntry = r.table("devices").get(ticketId).run(conn);

		if (dbEntry) {
			const filtered = dbEntry.devices.filter(it = > it.accountId != accountId);

			if (filtered.length > 0) {
				r.table("devices").update({
					id:ticketId,
					devices:filtered
				})
			} else {
				r.table("devices").get(ticketId).delete().run(conn);
			}

			return true;
		} else {
			return false;
		}
	}*/

	fun getLimit(ticketId: String) = if (GODS.contains(ticketId)) 20 else 3

	class Entry {
		@JvmField var id: String? = null
		@JvmField var devices: List<DeviceAuth>? = null
	}
}