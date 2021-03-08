package io.p8e.util

import com.google.protobuf.Descriptors.FieldDescriptor.Type.MESSAGE
import com.google.protobuf.Message
import com.google.protobuf.Timestamp
import io.p8e.proto.Util.AuditFields

const val DEFAULT_USER = "00000000-0000-0000-0000-000000000000"
const val DEFAULT_VERSION = 2

/**
 * If a message type has audit fields, properly populate them.
 */
fun <T : Message.Builder> T.auditedProv(modifiedBy: String = DEFAULT_USER, version: Int = DEFAULT_VERSION, message: String = "", time: Timestamp = Timestamp.getDefaultInstance().nowProv()): T = apply {
    val auditFieldsField = descriptorForType
        .fields
        .filter { it.type == MESSAGE }
        .firstOrNull { it.messageType == AuditFields.getDescriptor() } ?: return@apply

    val auditFields = getField(auditFieldsField) as AuditFields
    val newFields = if (auditFields.createdBy.isNullOrEmpty()) {
        newCreateAuditFields(modifiedBy, version, message, time)
    } else {
        newUpdateAuditFields(auditFields, modifiedBy, version, message, time)
    }

    setField(auditFieldsField, newFields)
}

private fun newCreateAuditFields(modifiedBy: String, version: Int = DEFAULT_VERSION, message: String = "", time: Timestamp): AuditFields =
    AuditFields.newBuilder()
        .setCreatedDate(time)
        .setCreatedBy(modifiedBy)
        .setVersion(version)
        .setMessage(message)
        .build()

private fun newUpdateAuditFields(auditField: AuditFields, modifiedBy: String, version: Int = DEFAULT_VERSION, message: String = "", time: Timestamp): AuditFields =
    AuditFields.newBuilder()
        .mergeFrom(auditField)
        .setUpdatedDate(time)
        .setUpdatedBy(modifiedBy)
        .setVersion(version)
        .setMessage(message)
        .build()
