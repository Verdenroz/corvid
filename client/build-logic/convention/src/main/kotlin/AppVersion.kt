import org.gradle.api.Project

/** The build's monotonic version code; CI passes `-Pcorvid.versionCode`. */
fun Project.appVersionCode(): Int = providers.gradleProperty("corvid.versionCode").map(String::toInt).getOrElse(1)

fun Project.appVersionName(): String = "0.1.${appVersionCode()}"
