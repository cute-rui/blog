package blog.writer.sync

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
        val waiting = TargetProgress(ProgressPhase.Idle, "还没有构建记录")
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
            building -> TargetProgress(ProgressPhase.Building, "构建中…", run.htmlUrl)
            failed -> TargetProgress(ProgressPhase.Failed, "失败：构建没有完成", run.htmlUrl, "查看日志")
            else -> TargetProgress(ProgressPhase.Success, "网站已上线", run.htmlUrl)
        }
    }
    val wechat = if (!wechatSelected) {
        null
    } else {
        when {
            building -> TargetProgress(ProgressPhase.Building, "构建中…", run.htmlUrl)
            failed -> TargetProgress(ProgressPhase.Failed, "失败：公众号版本没有生成", run.htmlUrl, "查看日志")
            else -> TargetProgress(ProgressPhase.Success, "公众号版本已就绪", run.htmlUrl)
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
            building -> TargetProgress(ProgressPhase.Building, "同步中…", run.htmlUrl)
            failed -> TargetProgress(
                ProgressPhase.Failed,
                "失败：没有找到草稿箱任务",
                run.htmlUrl,
                "公众号版本仍可复制",
            )
            else -> TargetProgress(ProgressPhase.Success, "已进入公众号草稿箱", run.htmlUrl)
        }
    } else {
        val jobBuilding = draftJob.status != "completed"
        val jobFailed = draftJob.status == "completed" && draftJob.conclusion != "success"
        when {
            jobBuilding -> TargetProgress(ProgressPhase.Building, "同步中…", run.htmlUrl)
            jobFailed -> TargetProgress(
                ProgressPhase.Failed,
                "失败：草稿箱同步没有完成",
                run.htmlUrl,
                "公众号版本仍可复制",
            )
            else -> TargetProgress(ProgressPhase.Success, "已进入公众号草稿箱", run.htmlUrl)
        }
    }
    return PublishProgress(blog, wechat, draft)
}
