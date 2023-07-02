package ch.pontius.kiar.api.routes.job

import ch.pontius.kiar.api.model.job.CreateJobRequest
import ch.pontius.kiar.api.model.job.Job
import ch.pontius.kiar.api.model.session.LoginRequest
import ch.pontius.kiar.api.model.status.ErrorStatus
import ch.pontius.kiar.api.model.status.ErrorStatusException
import ch.pontius.kiar.api.routes.session.currentUser
import ch.pontius.kiar.database.config.jobs.DbJobTemplate
import ch.pontius.kiar.database.institution.DbRole
import ch.pontius.kiar.database.institution.DbUser
import ch.pontius.kiar.database.job.DbJob
import ch.pontius.kiar.database.job.DbJobSource
import ch.pontius.kiar.database.job.DbJobStatus
import ch.pontius.kiar.utilities.mapToArray
import io.javalin.http.BadRequestResponse
import io.javalin.http.Context
import io.javalin.openapi.*
import jetbrains.exodus.database.TransientEntityStore
import kotlinx.dnq.query.*
import kotlinx.dnq.query.FilteringContext.eq
import kotlinx.dnq.util.findById
import org.joda.time.DateTime

@OpenApi(
    path = "/api/jobs/active",
    methods = [HttpMethod.GET],
    summary = "Retrieves all jobs that are currently active. Non-administrator users can only see Jobs that belong to them.",
    operationId = "getActiveJobs",
    tags = ["Job"],
    pathParams = [],
    responses = [
        OpenApiResponse("200", [OpenApiContent(Array<Job>::class)]),
        OpenApiResponse("401", [OpenApiContent(ErrorStatus::class)]),
        OpenApiResponse("403", [OpenApiContent(ErrorStatus::class)]),
        OpenApiResponse("500", [OpenApiContent(ErrorStatus::class)])
    ]
)
fun getActiveJobs(ctx: Context, store: TransientEntityStore) {
    store.transactional(true) {
        val currentUser = ctx.currentUser()
        val jobs = when (currentUser.role) {
            DbRole.ADMINISTRATOR -> DbJob.filter { it.status.active eq true }
            DbRole.MANAGER, DbRole.VIEWER -> {
                val participant = currentUser.institution?.participant
                if (participant == null) {
                    DbJob.emptyQuery()
                } else {
                    DbJob.filter { (it.status.active eq true) and (it.template?.participant eq participant) }
                }
            }
            else -> DbJob.emptyQuery()
        }
        ctx.json(jobs.mapToArray { it.toApi() })
    }
}

@OpenApi(
    path = "/api/jobs/inactive",
    methods = [HttpMethod.GET],
    summary = "Retrieves all jobs that are currently inactive (job history). Non-administrator users can only see Jobs that belong to them.",
    operationId = "getInactiveJobs",
    tags = ["Job"],
    pathParams = [],
    responses = [
        OpenApiResponse("200", [OpenApiContent(Array<Job>::class)]),
        OpenApiResponse("401", [OpenApiContent(ErrorStatus::class)]),
        OpenApiResponse("403", [OpenApiContent(ErrorStatus::class)]),
        OpenApiResponse("500", [OpenApiContent(ErrorStatus::class)])
    ]
)
fun getInactiveJobs(ctx: Context, store: TransientEntityStore) {
    store.transactional(true) {
        val currentUser = ctx.currentUser()
        val jobs = when (currentUser.role) {
            DbRole.ADMINISTRATOR -> DbJob.filter { it.status.active eq false }
            DbRole.MANAGER, DbRole.VIEWER -> {
                val participant = currentUser.institution?.participant
                if (participant == null) {
                    DbJob.emptyQuery()
                } else {
                    DbJob.filter { (it.status.active eq false) and (it.template?.participant eq participant) }
                }
            }
            else -> DbJob.emptyQuery()
        }
        ctx.json(jobs.mapToArray { it.toApi() })
    }
}


@OpenApi(
    path = "/api/jobs",
    methods = [HttpMethod.POST],
    summary = "Creates a new job.",
    operationId = "postCreateJob",
    tags = ["Job"],
    requestBody = OpenApiRequestBody([OpenApiContent(CreateJobRequest::class)], required = true),
    pathParams = [],
    responses = [
        OpenApiResponse("200", [OpenApiContent(Job::class)]),
        OpenApiResponse("400", [OpenApiContent(ErrorStatus::class)]),
        OpenApiResponse("401", [OpenApiContent(ErrorStatus::class)]),
        OpenApiResponse("403", [OpenApiContent(ErrorStatus::class)]),
        OpenApiResponse("404", [OpenApiContent(ErrorStatus::class)]),
        OpenApiResponse("500", [OpenApiContent(ErrorStatus::class)])
    ]
)
fun createJob(ctx: Context, store: TransientEntityStore) {
    val request = try {
        ctx.bodyAsClass(CreateJobRequest::class.java)
    } catch (e: BadRequestResponse) {
        throw ErrorStatusException(400, "Malformed request.")
    }

    /* Create new job. */
    val job = store.transactional {
        val currentUser = ctx.currentUser()
        val template = try {
            DbJobTemplate.findById(request.templateId)
        } catch (e: Throwable) {
            throw ErrorStatusException(404, "Job template with ID ${request.templateId} could not be found.")
        }

        /* Check if user's participant is the same as the one associated with the template. */
        if (currentUser.role != DbRole.ADMINISTRATOR && template.participant != currentUser.institution?.participant) {
            throw ErrorStatusException(403, "You are not allowed to create a job for template ${template.xdId}.")
        }

        /* Create new job. */
        DbJob.new {
            this.name = request.jobName ?: (template.name + "-${System.currentTimeMillis()}")
            this.template = template
            this.source = DbJobSource.WEB
            this.status = DbJobStatus.CREATED
            this.createdAt = DateTime.now()
            this.createdBy = currentUser
            this.createdByName = currentUser.name
        }.toApi()
    }

    /* Return job object. */
    ctx.json(job)
}