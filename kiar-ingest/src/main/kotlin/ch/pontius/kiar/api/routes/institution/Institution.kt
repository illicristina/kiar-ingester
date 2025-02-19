import ch.pontius.kiar.api.model.institution.Institution
import ch.pontius.kiar.api.model.institution.PaginatedInstitutionResult
import ch.pontius.kiar.api.model.status.ErrorStatus
import ch.pontius.kiar.api.model.status.ErrorStatusException
import ch.pontius.kiar.api.model.status.SuccessStatus
import ch.pontius.kiar.api.routes.session.currentUser
import ch.pontius.kiar.database.institution.DbInstitution
import ch.pontius.kiar.database.institution.DbParticipant
import ch.pontius.kiar.database.institution.DbRole
import ch.pontius.kiar.database.masterdata.DbRightStatement
import ch.pontius.kiar.utilities.mapToArray
import io.javalin.http.BadRequestResponse
import io.javalin.http.Context
import io.javalin.openapi.*
import jetbrains.exodus.database.TransientEntityStore
import kotlinx.dnq.query.*
import kotlinx.dnq.util.findById
import org.joda.time.DateTime

@OpenApi(
    path = "/api/institutions",
    methods = [HttpMethod.GET],
    summary = "Retrieves all institutions registered in the database.",
    operationId = "getInstitutions",
    tags = ["Institution"],
    pathParams = [],
    queryParams = [
        OpenApiParam(name = "page", type = Int::class, description = "The page index (zero-based) for pagination.", required = false),
        OpenApiParam(name = "pageSize", type = Int::class, description = "The page size for pagination.", required = false),
        OpenApiParam(name = "order", type = String::class, description = "The attribute to order by. Possible values are 'name', 'city', 'zip', 'canton' and 'publish'.", required = false),
        OpenApiParam(name = "orderDir", type = String::class, description = "The sort order. Possible values are 'asc' and 'desc'.", required = false)
    ],
    responses = [
        OpenApiResponse("200", [OpenApiContent(PaginatedInstitutionResult::class)]),
        OpenApiResponse("401", [OpenApiContent(ErrorStatus::class)]),
        OpenApiResponse("403", [OpenApiContent(ErrorStatus::class)]),
        OpenApiResponse("500", [OpenApiContent(ErrorStatus::class)])
    ]
)
fun getListInstitutions(ctx: Context, store: TransientEntityStore) {
    val page = ctx.queryParam("page")?.toIntOrNull() ?: 0
    val pageSize = ctx.queryParam("pageSize")?.toIntOrNull() ?: 50
    val order = ctx.queryParam("order")?.lowercase() ?: "name"
    val orderDir = ctx.queryParam("orderDir")?.lowercase() ?: "asc"
    val result = store.transactional(true) {
        val institutions = when(order) {
            "city" -> DbInstitution.all().sortedBy(DbInstitution::city, orderDir == "asc")
            "zip" -> DbInstitution.all().sortedBy(DbInstitution::zip, orderDir == "asc")
            "canton" -> DbInstitution.all().sortedBy(DbInstitution::canton, orderDir == "asc")
            "publish" -> DbInstitution.all().sortedBy(DbInstitution::publish, orderDir == "asc")
            else -> DbInstitution.all().sortedBy(DbInstitution::name, orderDir == "asc")
        }.drop(page * pageSize).take(pageSize).mapToArray { it.toApi() }
        val total = DbInstitution.all().size()
        total to institutions

    }
    ctx.json(PaginatedInstitutionResult(result.first, page, pageSize, result.second))
}

@OpenApi(
    path = "/api/institutions",
    methods = [HttpMethod.POST],
    summary = "Creates a new institution.",
    operationId = "postCreateInstitution",
    tags = ["Institution"],
    requestBody = OpenApiRequestBody([OpenApiContent(Institution::class)], required = true),
    pathParams = [],
    responses = [
        OpenApiResponse("200", [OpenApiContent(SuccessStatus::class)]),
        OpenApiResponse("400", [OpenApiContent(ErrorStatus::class)]),
        OpenApiResponse("401", [OpenApiContent(ErrorStatus::class)]),
        OpenApiResponse("403", [OpenApiContent(ErrorStatus::class)]),
        OpenApiResponse("404", [OpenApiContent(ErrorStatus::class)]),
        OpenApiResponse("500", [OpenApiContent(ErrorStatus::class)])
    ]
)
fun postCreateInstitution(ctx: Context, store: TransientEntityStore) {
    val request = try {
        ctx.bodyAsClass(Institution::class.java)
    } catch (e: BadRequestResponse) {
        throw ErrorStatusException(400, "Malformed request.")
    }

    /* Create new job. */
    val institution = store.transactional {
        /* Create new job. */
        DbInstitution.new {
            this.name = request.name
            this.displayName = request.displayName
            this.description = request.description
            this.isil = request.isil
            this.street = request.street
            this.city = request.city
            this.zip = request.zip
            this.canton = request.canton
            this.publish = request.publish
            this.email = request.email
            this.homepage = request.homepage
            this.participant = DbParticipant.filter { it.name eq request.participantName }.firstOrNull()
                ?: throw ErrorStatusException(404, "Participant ${request.participantName} could not be found.")
            this.createdAt = DateTime.now()
            this.changedAt = DateTime.now()
        }.toApi()
    }

    /* Return job object. */
    ctx.json(SuccessStatus("Institution '${institution.name}' (id: ${institution.id}) created successfully."))
}

@OpenApi(
    path = "/api/institutions/{id}",
    methods = [HttpMethod.PUT],
    summary = "Updates an existing institution.",
    operationId = "putUpdateInstitution",
    tags = ["Institution"],
    requestBody = OpenApiRequestBody([OpenApiContent(Institution::class)], required = true),
    pathParams = [
        OpenApiParam(name = "id", description = "The ID of the institution that should be updated.", required = true)
    ],
    responses = [
        OpenApiResponse("200", [OpenApiContent(SuccessStatus::class)]),
        OpenApiResponse("400", [OpenApiContent(ErrorStatus::class)]),
        OpenApiResponse("401", [OpenApiContent(ErrorStatus::class)]),
        OpenApiResponse("403", [OpenApiContent(ErrorStatus::class)]),
        OpenApiResponse("404", [OpenApiContent(ErrorStatus::class)]),
        OpenApiResponse("500", [OpenApiContent(ErrorStatus::class)])
    ]
)
fun putUpdateInstitution(ctx: Context, store: TransientEntityStore) {
    val institutionId = ctx.pathParam("id")
    val request = try {
        ctx.bodyAsClass(Institution::class.java)
    } catch (e: BadRequestResponse) {
        throw ErrorStatusException(400, "Malformed request.")
    }

    /* Create new job. */
    val institutionName = store.transactional {
        val institution = try {
            DbInstitution.findById(institutionId)
        } catch (e: Throwable) {
            throw ErrorStatusException(404, "Institution with ID $institutionId could not be found.")
        }

        /* Make sure, that the current user can actually edit this institution. */
        val currentUser = ctx.currentUser()
        if (currentUser.role != DbRole.ADMINISTRATOR && currentUser.institution != institution) {
            throw ErrorStatusException(403, "Institution with ID $institutionId cannot be editet by current user.")
        }

        /* Update institution. */
        institution.displayName = request.displayName
        institution.description = request.description
        institution.isil = request.isil
        institution.street = request.street
        institution.city = request.city
        institution.zip = request.zip
        institution.email = request.email
        institution.homepage = request.homepage
        institution.defaultCopyright = request.defaultCopyright
        institution.defaultRightStatement = DbRightStatement.filter { it.short eq request.defaultRightStatement }.singleOrNull()
        institution.changedAt = DateTime.now()

        /* Some data can only be edited by an administrator. */
        if (currentUser.role == DbRole.ADMINISTRATOR) {
            institution.name = request.name
            institution.participant = DbParticipant.filter { it.name eq request.participantName }.firstOrNull() ?: throw ErrorStatusException(404, "Participant ${request.participantName} could not be found.")
            institution.canton = request.canton
            institution.publish = request.publish
        }


        institution.name
    }

    /* Return job object. */
    ctx.json(SuccessStatus("Institution '$institutionName' (id: $institutionId) updated successfully."))
}

@OpenApi(
    path = "/api/institutions/{id}",
    methods = [HttpMethod.DELETE],
    summary = "Deletes an existing institution.",
    operationId = "deleteInstitution",
    tags = ["Institution"],
    pathParams = [
        OpenApiParam(name = "id", description = "The ID of the institution that should be deleted.", required = true)
    ],
    responses = [
        OpenApiResponse("200", [OpenApiContent(SuccessStatus::class)]),
        OpenApiResponse("401", [OpenApiContent(ErrorStatus::class)]),
        OpenApiResponse("403", [OpenApiContent(ErrorStatus::class)]),
        OpenApiResponse("404", [OpenApiContent(ErrorStatus::class)]),
        OpenApiResponse("500", [OpenApiContent(ErrorStatus::class)])
    ]
)
fun deleteInstitution(ctx: Context, store: TransientEntityStore) {
    val institutionId = ctx.pathParam("id")
    val institutionName = store.transactional {
        val institution = try {
            DbInstitution.findById(institutionId)
        } catch (e: Throwable) {
            throw ErrorStatusException(404, "Institution with ID $institutionId could not be found.")
        }
        val name = institution.name
        institution.delete()
        name
    }
    ctx.json(SuccessStatus("Institution '$institutionName' (id: $institutionId) deleted successfully."))
}


