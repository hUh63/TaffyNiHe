package top.wkbin.taixu.di.project.template

import org.koin.dsl.module
import top.wkbin.taixu.template.ProjectTemplateEngine
import top.wkbin.taixu.template.ProjectTemplateStore

/** Dependency registrations owned by the project-template module. */
val projectTemplateModule = module {
    single<ProjectTemplateEngine> { ProjectTemplateEngine(context = get()) }

    single<ProjectTemplateStore> { ProjectTemplateStore(context = get()) }
}
