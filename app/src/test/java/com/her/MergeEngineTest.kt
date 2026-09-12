package com.her

import android.app.Application
import com.her.data.drive.MergeEngine
import com.her.domain.GroceryStatus
import com.her.domain.SyncOp
import com.her.domain.SyncOpType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class MergeEngineTest {
    @Test
    fun purchasedSurvivesLaterAddFromOtherDevice() {
        val add = SyncOp(
            id = "a",
            entityType = "groceries",
            entityId = "milk",
            opType = SyncOpType.UPSERT,
            payloadJson = groceryJson("milk", GroceryStatus.ACTIVE, 10_03_000L, "A", 1),
            createdAt = 10_03_000L,
            deviceId = "A",
            seq = 1,
            uploaded = true,
        )
        val purchased = SyncOp(
            id = "b",
            entityType = "groceries",
            entityId = "milk",
            opType = SyncOpType.UPSERT,
            payloadJson = groceryJson("milk", GroceryStatus.PURCHASED, 10_20_000L, "B", 2),
            createdAt = 10_20_000L,
            deviceId = "B",
            seq = 1,
            uploaded = true,
        )
        val merged = MergeEngine.applyOps(null, listOf(add, purchased))!!
        assertEquals(GroceryStatus.PURCHASED.name, merged.getString("status"))
    }

    private fun groceryJson(name: String, status: GroceryStatus, updatedAt: Long, deviceId: String, version: Long): String {
        val obj = JSONObject()
        obj.put("id", name)
        obj.put("name", name)
        obj.put("status", status.name)
        obj.put("updatedAt", updatedAt)
        obj.put("deviceId", deviceId)
        obj.put("version", version)
        return obj.toString()
    }
}
