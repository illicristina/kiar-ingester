package ch.pontius.kiar.database.config.mapping

import ch.pontius.kiar.api.model.config.mappings.MappingType
import jetbrains.exodus.entitystore.Entity
import kotlinx.dnq.XdEnumEntity
import kotlinx.dnq.XdEnumEntityType
import kotlinx.dnq.xdRequiredStringProp

/**
 * Enumeration of the types of [DbEntityMapping].
 *
 * @author Ralph Gasser
 * @version 1.0.0
 */
class DbFormat(entity: Entity): XdEnumEntity(entity) {
    companion object : XdEnumEntityType<DbFormat>() {
        val XML by DbFormat.enumField { description = "XML" }
        val JSON by DbFormat.enumField { description = "JSON" }
    }

    /** The name of this [DbEntityMapping]. */
    var description by xdRequiredStringProp(unique = true)

    /**
     * Convenience method used to convert this [DbFormat] to a [MappingType].
     *
     * @return [MappingType]
     */
    fun toApi() = MappingType.valueOf(this.description)
}