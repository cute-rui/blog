package blog.writer.sync
import blog.writer.copy.Copy

enum class ProgressPhase { Idle, Building, Success, Failed }

data class TargetProgress(
    val phase: ProgressPhase,
    val label: String,
    val url: String? = null,
    val detail: String? = null,
)

data class PublishProgress(
    val blog: TargetProgress?,
    val wechat: TargetProgress?,
    val draft: TargetProgress?,
)

fun mapPublishProgress(
    run: WorkflowRunSummary?,
    blogSelected: Boolean,
    wechatSelected: Boolean,
    wechatMode: String,
): PublishProgress {
    if (!blogSelected && !wechatSelected) {
        return PublishProgress(blog = null, wechat = null, draft = null)
    }
    if (run == null) {
        val waiting = TargetProgress(ProgressPhase.Idle, Copy["progress_no_build"])
        return PublishProgress(
            blog = waiting.takeIf { blogSelected },
            wechat = waiting.takeIf { wechatSelected },
            draft = null,
        )
    }
    val building = run.status == "queued" || run.status == "in_progress" || run.status == "waiting" || run.status == "pending"
    val failed = run.status == "completed" && run.conclusion != "success" && run.conclusion != "skipped"
    val blog = if (!blogSelected) {
        null
    } else {
        when {
            building -> TargetProgress(ProgressPhase.Building, Copy["progress_building"], run.htmlUrl)
            failed -> TargetProgress(ProgressPhase.Failed, Copy["progress_build_failed"], run.htmlUrl, Copy["view_log"])
            else -> TargetProgress(ProgressPhase.Success, Copy["site_live"], run.htmlUrl)
        }
    }
    val wechat = if (!wechatSelected) {
        null
    } else {
        when {
            building -> TargetProgress(ProgressPhase.Building, Copy["progress_building"], run.htmlUrl)
            failed -> TargetProgress(ProgressPhase.Failed, Copy["wechat_build_failed"], run.htmlUrl, Copy["view_log"])
            else -> TargetProgress(ProgressPhase.Success, Copy["wechat_build_ready"], run.htmlUrl)
        }
    }
    val draftJob = run.jobs.firstOrNull { job ->
        val name = job.name.lowercase()
        name.contains("wechat") || name.contains("draft")
    }
    val draft = if (wechatMode != "draft" || !wechatSelected) {
        null
    } else if (draftJob == null) {
        when {
            building -> TargetProgress(ProgressPhase.Building, Copy["syncing"], run.htmlUrl)
            failed -> TargetProgress(
                ProgressPhase.Failed,
                Copy["draft_job_missing"],
                run.htmlUrl,
                Copy["wechat_still_copyable"],
            )
            else -> TargetProgress(ProgressPhase.Success, Copy["entered_drafts"], run.htmlUrl)
        }
    } else {
        val jobBuilding = draftJob.status != "completed"
        val jobFailed = draftJob.status == "completed" && draftJob.conclusion != "success"
        when {
            jobBuilding -> TargetProgress(ProgressPhase.Building, Copy["syncing"], run.htmlUrl)
            jobFailed -> TargetProgress(
                ProgressPhase.Failed,
                Copy["draft_sync_failed"],
                run.htmlUrl,
                Copy["wechat_still_copyable"],
            )
            else -> TargetProgress(ProgressPhase.Success, Copy["entered_drafts"], run.htmlUrl)
        }
    }
    return PublishProgress(blog, wechat, draft)
}
