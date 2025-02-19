package ch.pontius.kiar.ingester.processors.transformers

import ch.pontius.kiar.api.model.job.JobLog
import ch.pontius.kiar.api.model.job.JobLogContext
import ch.pontius.kiar.api.model.job.JobLogLevel
import ch.pontius.kiar.database.institution.DbInstitution
import ch.pontius.kiar.ingester.processors.ProcessingContext
import ch.pontius.kiar.ingester.processors.sources.Source
import ch.pontius.kiar.ingester.solrj.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.dnq.query.asSequence
import kotlinx.dnq.query.filter
import org.apache.logging.log4j.LogManager
import org.apache.solr.common.SolrInputDocument

/**
 * A [Transformer] that a) validates incoming [SolrInputDocument]s with respect to required fields, b) checks and correct the association of the document
 * with a present [DbInstitution] and participant and c) enriches verified documents with meta information that can be derived from the [DbInstitution].
 *
 * @author Ralph Gasser
 * @version 1.1.0
 */
class InstitutionTransformer(override val input: Source<SolrInputDocument>): Transformer<SolrInputDocument, SolrInputDocument> {

    companion object {
        private val LOGGER = LogManager.getLogger(InstitutionTransformer::class.java)
    }

    private val institutions = DbInstitution.filter { (it.publish eq true) }.asSequence().associate { it.name to it.toApi() }


    /**
     * Converts this [InstitutionTransformer] to a [Flow]
     *
     * @param context The [ProcessingContext] for the [Flow]
     * @return [Flow]
     */
    override fun toFlow(context: ProcessingContext): Flow<SolrInputDocument> {
        return this.input.toFlow(context).filter { doc ->
            /* Fetch institution field from document. */
            val uuid = doc.asString(Field.UUID)
            if (uuid == null) {
                LOGGER.error("Failed to verify document: Field 'uuid' is missing (jobId = {}, participantId = {}, docId = {}).", context.jobId, context.participant, uuid)
                context.log.add(JobLog(null, "<undefined>", null, JobLogContext.METADATA, JobLogLevel.SEVERE, "Document skipped: Field 'uuid' is missing."))
                return@filter false
            }

            /* Validate institution entry; if it's missing, try to derive it from the participant. */
            var institutionName = doc.asString(Field.INSTITUTION)
            if (institutionName == null) {
                /* We can now try to derive the institution from the participant. */
                val institution = institutions.values.singleOrNull { it.participantName == context.participant }
                if (institution == null) {
                    LOGGER.warn("Failed to verify document: Field institution could not be derived from participant (jobId = {}, participantId = {}, docId = {}).", context.jobId, context.participant, uuid)
                    context.log.add(JobLog(null, uuid, null, JobLogContext.METADATA, JobLogLevel.ERROR, "Document skipped: Fields 'institution' and '_participant_' are missing."))
                    return@filter false
                }
                doc.setField(Field.INSTITUTION, institution.name)
                institutionName = institution.name
            }

            /* Fetch corresponding database entry. */
            val entry = institutions[institutionName]
            if (entry == null) {
                LOGGER.warn("Failed to verify document: Institution '$institutionName' is unknown (jobId = {}, participantId = {}, docId = {}).", context.jobId, context.participant, uuid)
                context.log.add(JobLog(null, uuid, null, JobLogContext.METADATA, JobLogLevel.WARNING, "Document skipped: Could not find database entry for institution '${institutionName}'."))
                return@filter false
            }

            /* Enrich Apache Solr with institution-based information. */
            if (!doc.has(Field.PARTICIPANT)) {
                doc.setField(Field.PARTICIPANT, entry.participantName)
            }
            if (!doc.has(Field.CANTON)) {
                doc.setField(Field.CANTON, entry.canton)
            }
            if (!doc.has(Field.COPYRIGHT) && entry.defaultCopyright != null) {
                doc.setField(Field.COPYRIGHT, entry.defaultCopyright)
            }
            if (!doc.has(Field.RIGHTS_STATEMENT) && entry.defaultRightStatement != null) {
                doc.setField(Field.RIGHTS_STATEMENT, entry.defaultRightStatement)
            }
            //if (!doc.has(Field.OWNER)) {
            //    doc.setField(Field.OWNER, entry.displayName)
            //}
            /* Return true. */
            return@filter true
        }
    }
}