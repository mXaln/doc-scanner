package org.bibletranslationtools.docscanner.di

import org.bibletranslationtools.database.MainDatabase
import org.bibletranslationtools.docscanner.api.HtrLogin
import org.bibletranslationtools.docscanner.api.TranscriberApi
import org.bibletranslationtools.docscanner.api.UpdateLanguages
import org.bibletranslationtools.docscanner.data.models.Project
import org.bibletranslationtools.docscanner.data.repository.BookRepository
import org.bibletranslationtools.docscanner.data.repository.BookRepositoryImpl
import org.bibletranslationtools.docscanner.data.repository.LanguageRepository
import org.bibletranslationtools.docscanner.data.repository.LanguageRepositoryImpl
import org.bibletranslationtools.docscanner.data.repository.LevelRepository
import org.bibletranslationtools.docscanner.data.repository.LevelRepositoryImpl
import org.bibletranslationtools.docscanner.data.repository.PdfRepository
import org.bibletranslationtools.docscanner.data.repository.PdfRepositoryImpl
import org.bibletranslationtools.docscanner.data.repository.PreferenceRepository
import org.bibletranslationtools.docscanner.data.repository.ProjectRepository
import org.bibletranslationtools.docscanner.data.repository.ProjectRepositoryImpl
import org.bibletranslationtools.docscanner.data.repository.SettingsPreferenceRepository
import org.bibletranslationtools.docscanner.ocr.RecognizerModels
import org.bibletranslationtools.docscanner.ui.viewmodel.HomeViewModel
import org.bibletranslationtools.docscanner.ui.viewmodel.LoginViewModel
import org.bibletranslationtools.docscanner.ui.viewmodel.ProjectViewModel
import org.bibletranslationtools.docscanner.ui.viewmodel.SettingsViewModel
import org.bibletranslationtools.docscanner.ui.viewmodel.SplashViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

expect val platformModule: Module

val sharedModule = module {
    single { MainDatabase(get()) }
    singleOf(::TranscriberApi)
    singleOf(::UpdateLanguages)
    singleOf(::RecognizerModels)

    singleOf(::SettingsPreferenceRepository).bind<PreferenceRepository>()

    // Database repositories
    singleOf(::ProjectRepositoryImpl).bind<ProjectRepository>()
    singleOf(::PdfRepositoryImpl).bind<PdfRepository>()
    singleOf(::LanguageRepositoryImpl).bind<LanguageRepository>()
    singleOf(::BookRepositoryImpl).bind<BookRepository>()
    singleOf(::LevelRepositoryImpl).bind<LevelRepository>()

    // View models
    factoryOf(::SplashViewModel)
    factoryOf(::HomeViewModel)
    factoryOf(::SettingsViewModel)
    factoryOf(::LoginViewModel)
    factory { (project: Project) ->
        ProjectViewModel(project, get(), get(), get(), get(), get())
    }
    factoryOf(::HtrLogin)
}