pluginManagement {
    repositories {
        // Add Appian's repository for plugins
        maven {
            url = uri("https://repo.appian.com/artifactory/appian-gradle-plugins/")
        }
        // Also include the default Gradle Plugin Portal
        gradlePluginPortal()
    }
}

rootProject.name = "OpenHTMLToPDFUtils"