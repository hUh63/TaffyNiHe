package top.wkbin.taixu.di.feature.bot

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import top.wkbin.taixu.core.database.repository.BotMemberGroupRepository
import top.wkbin.taixu.core.database.repository.BotScheduleRepository
import top.wkbin.taixu.core.database.repository.GroupChatRepository
import top.wkbin.taixu.core.database.repository.PersonaBotRepository
import top.wkbin.taixu.harness.bot.schedule.BotScheduleWakeup
import top.wkbin.taixu.ui.bot.BotDetailViewModel
import top.wkbin.taixu.ui.bot.BotListViewModel
import top.wkbin.taixu.ui.bot.GroupChatViewModel

/** Dependency registrations owned by the feature:bot module. */
val featureBotModule = module {
    viewModel<BotListViewModel> {
        BotListViewModel(
            context = get(),
            botRepository = get<PersonaBotRepository>(),
            groupRepository = get<GroupChatRepository>(),
            memberGroupRepository = get<BotMemberGroupRepository>(),
            scheduleRepository = get<BotScheduleRepository>(),
            orchestrator = get(),
            marketRepository = get(),
            packageService = get(),
            models = get(),
            wakeup = getOrNull<BotScheduleWakeup>(),
        )
    }
    viewModel<BotDetailViewModel> {
        BotDetailViewModel(
            context = get(),
            botRepository = get<PersonaBotRepository>(),
            groupRepository = get<GroupChatRepository>(),
            scheduleRepository = get<BotScheduleRepository>(),
            orchestrator = get(),
            memoryManager = get(),
            models = get(),
            packageService = get(),
            wakeup = getOrNull<BotScheduleWakeup>(),
        )
    }
    viewModel<GroupChatViewModel> {
        GroupChatViewModel(
            context = get(),
            groupRepository = get<GroupChatRepository>(),
            botRepository = get<PersonaBotRepository>(),
            dispatcher = get(),
            exporter = get(),
            groupSessions = get(),
            approvals = get(),
            sessionControl = get(),
            assignments = get(), assignmentRuntime = get(),
            orchestrator = get(),
            scheduleRepository = get<BotScheduleRepository>(),
        )
    }
}
