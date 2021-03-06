// Copyright 2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.lambda.execution.sam

import com.intellij.compiler.CompilerTestUtil
import com.intellij.debugger.DebuggerManagerEx
import com.intellij.debugger.engine.DebugProcessAdapterImpl
import com.intellij.debugger.engine.SuspendContextImpl
import com.intellij.debugger.impl.DebuggerManagerListener
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.debugger.impl.PrioritizedTask
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.Output
import com.intellij.execution.OutputListener
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.projectRoots.impl.JavaAwareProjectJdkTableImpl
import com.intellij.openapi.roots.CompilerProjectExtension
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiJavaFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.runInEdtAndWait
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import software.aws.toolkits.jetbrains.settings.SamSettings
import software.aws.toolkits.jetbrains.testutils.rules.HeavyJavaCodeInsightTestFixtureRule
import software.aws.toolkits.jetbrains.testutils.rules.addClass
import software.aws.toolkits.jetbrains.testutils.rules.addModule
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class SamRunConfigurationIntegrationTest {
    @Rule
    @JvmField
    val projectRule = HeavyJavaCodeInsightTestFixtureRule()

    @Before
    fun setUp() {
        SamSettings.getInstance().executablePath = System.getenv().getOrDefault("SAM_CLI_EXEC", "sam")

        val fixture = projectRule.fixture
        val module = fixture.addModule("main")
        val psiClass = fixture.addClass(
            module,
            """
            package com.example;

            public class LambdaHandler {
                public String handleRequest(String request) {
                    return request.toUpperCase();
                }
            }
            """
        )
        runInEdtAndWait {
            fixture.openFileInEditor(psiClass.containingFile.virtualFile)
        }

        setUpCompiler()
    }

    private fun setUpCompiler() {
        val project = projectRule.project
        val modules = ModuleManager.getInstance(project).modules
        CompilerTestUtil.enableExternalCompiler()

        WriteCommandAction.writeCommandAction(project).run<Nothing> {
            val compilerExtension = CompilerProjectExtension.getInstance(project)!!
            compilerExtension.compilerOutputUrl = projectRule.fixture.tempDirFixture.findOrCreateDir("out").url
            val sdk = JavaAwareProjectJdkTableImpl.getInstanceEx().internalJdk

            for (module in modules) {
                ModuleRootModificationUtil.setModuleSdk(module, sdk)
            }
        }

        runInEdtAndWait {
            PlatformTestUtil.saveProject(project)
            CompilerTestUtil.saveApplicationSettings()
        }
    }

    @After
    fun tearDown() {
        CompilerTestUtil.disableExternalCompiler(projectRule.project)
    }

    @Test
    fun samIsExecuted() {
        val runConfiguration = createRunConfiguration(project = projectRule.project, input = "\"Hello World\"")
        assertThat(runConfiguration).isNotNull

        val executeLambda = executeLambda(runConfiguration)
        assertThat(executeLambda.exitCode).isEqualTo(0)
        assertThat(executeLambda.stdout).contains("HELLO WORLD")
    }

    @Test
    @Ignore("Fails to attach debugger under CodeBuild")
    fun samIsExecutedWithDebugger() {
        val debugManager = DebuggerManagerEx.getInstanceEx(projectRule.project)
        runInEdtAndWait {
            val breakpointManager = debugManager.breakpointManager

            val document = projectRule.fixture.editor.document
            val lambdaClass = projectRule.fixture.file as PsiJavaFile
            val lambdaBody = lambdaClass.classes[0].allMethods[0].body!!.statements[0]
            val lineNumber = document.getLineNumber(lambdaBody.textOffset)
            assertThat(breakpointManager.addLineBreakpoint(document, lineNumber)).isNotNull
        }

        val runConfiguration = createRunConfiguration(project = projectRule.project, input = "\"Hello World\"")
        assertThat(runConfiguration).isNotNull

        var breakpointHit = false

        debugManager.addDebuggerManagerListener(object : DebuggerManagerListener {
            override fun sessionAttached(session: DebuggerSession) {
                println("Debugger attached")
                val debugProcess = session.process
                debugProcess.addDebugProcessListener(object : DebugProcessAdapterImpl() {
                    override fun paused(suspendContext: SuspendContextImpl) {
                        println("Resuming: $suspendContext")
                        breakpointHit = true
                        debugProcess.managerThread.schedule(
                            debugProcess.createResumeCommand(
                                suspendContext,
                                PrioritizedTask.Priority.LOWEST
                            )
                        )
                    }
                })
            }
        })

        val executeLambda = executeLambda(runConfiguration, DefaultDebugExecutor.EXECUTOR_ID)

        assertThat(executeLambda.exitCode).isEqualTo(0)
        assertThat(executeLambda.stdout).contains("HELLO WORLD")

        assertThat(breakpointHit).isTrue()
    }

    private fun executeLambda(
        runConfiguration: SamRunConfiguration,
        executorId: String = DefaultRunExecutor.EXECUTOR_ID
    ): Output {
        val executor = ExecutorRegistry.getInstance().getExecutorById(executorId)
        val executionEnvironment = ExecutionEnvironmentBuilder.create(executor, runConfiguration).build()
        val executionFuture = CompletableFuture<Output>()
        runInEdt {
            executionEnvironment.runner.execute(executionEnvironment) {
                it.processHandler?.addProcessListener(object : OutputListener() {
                    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                        super.onTextAvailable(event, outputType)
                        println("SAM CLI: ${event.text}")
                    }

                    override fun processTerminated(event: ProcessEvent) {
                        super.processTerminated(event)
                        executionFuture.complete(this.output)
                    }
                })
            }
        }

        return executionFuture.get(3, TimeUnit.MINUTES)
    }
}