/*
 * Copyright (c) 2021 dzikoysk
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.reposilite.maven.specification

import com.reposilite.config.Configuration.RepositoryConfiguration
import com.reposilite.journalist.backend.InMemoryLogger
import com.reposilite.maven.MavenFacade
import com.reposilite.maven.api.DocumentInfo
import com.reposilite.maven.api.LookupRequest
import com.reposilite.maven.api.UNKNOWN_LENGTH
import com.reposilite.maven.application.MavenWebConfiguration
import com.reposilite.shared.FakeRemoteClient
import com.reposilite.shared.append
import com.reposilite.shared.getSimpleName
import com.reposilite.shared.safeResolve
import com.reposilite.shared.toPath
import com.reposilite.token.api.AccessToken
import com.reposilite.token.api.Route
import com.reposilite.token.api.RoutePermission
import com.reposilite.web.http.errorResponse
import io.javalin.http.ContentType
import io.javalin.http.HttpCode.NOT_FOUND
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import panda.std.asSuccess
import java.io.File
import java.nio.file.Files

@Suppress("LeakingThis")
internal abstract class MavenSpecification {

    protected companion object {
        val UNAUTHORIZED: AccessToken? = null
        const val REMOTE_REPOSITORY = "https://domain.com/releases"
        const val REMOTE_AUTH = "panda@secret"
        const val REMOTE_CONTENT = "content"
    }

    @TempDir
    @JvmField
    protected var workingDirectory: File? = null
    protected lateinit var mavenFacade: MavenFacade

    abstract fun repositories(): Map<String, RepositoryConfiguration>

    @BeforeEach
    private fun initializeFacade() {
        val logger = InMemoryLogger()
        val remoteClient = FakeRemoteClient { uri, credentials, _, _ ->
            if (uri.startsWith(REMOTE_REPOSITORY) && REMOTE_AUTH == credentials)
                DocumentInfo(
                    uri.replace(":", "").toPath().getSimpleName(),
                    ContentType.TEXT_XML,
                    UNKNOWN_LENGTH,
                    { REMOTE_CONTENT.byteInputStream() }
                ).asSuccess()
            else
                errorResponse(NOT_FOUND, "Not found")
        }
        this.mavenFacade = MavenWebConfiguration.createFacade(logger, workingDirectory!!.toPath(), remoteClient, repositories())
    }

    data class FileSpec(
        val repository: String,
        val gav: String,
        val content: String
    ) {

        fun toLookupRequest(authentication: AccessToken?): LookupRequest =
            LookupRequest(authentication, repository, gav)

    }

    fun createRepository(name: String, initializer: RepositoryConfiguration.() -> Unit): Pair<String, RepositoryConfiguration> =
        Pair(name, RepositoryConfiguration().also { initializer(it) })

    fun findRepositories(accessToken: AccessToken?): Collection<String> =
        mavenFacade.findRepositories(accessToken).files.map { it.name }

    fun addFileToRepository(fileSpec: FileSpec): FileSpec {
        workingDirectory!!.toPath()
            .safeResolve("repositories")
            .safeResolve(fileSpec.repository)
            .append(fileSpec.gav)
            .peek {
                Files.createDirectories(it.parent)
                Files.createFile(it)
                Files.write(it, fileSpec.content.toByteArray())
            }
            .get()

        return fileSpec
    }

    fun createAccessToken(name: String, secret: String, repository: String, gav: String, permission: RoutePermission): AccessToken {
        val routes = setOf(Route("/$repository/$gav", setOf(permission)))
        return AccessToken(name = name, secret = secret, routes = routes)
    }

}