/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.workers.internal;

import org.gradle.api.file.ProjectLayout;
import org.gradle.api.internal.ClassPathRegistry;
import org.gradle.initialization.ClassLoaderRegistry;
import org.gradle.initialization.GradleUserHomeDirProvider;
import org.gradle.initialization.layout.ProjectCacheDir;
import org.gradle.internal.classpath.CachedClasspathTransformer;
import org.gradle.internal.concurrent.ExecutorFactory;
import org.gradle.internal.concurrent.WorkerLimits;
import org.gradle.internal.event.ListenerManager;
import org.gradle.internal.hash.ClassLoaderHierarchyHasher;
import org.gradle.internal.instantiation.InstantiatorFactory;
import org.gradle.internal.isolation.IsolatableFactory;
import org.gradle.internal.logging.LoggingManagerInternal;
import org.gradle.internal.operations.BuildOperationRunner;
import org.gradle.internal.service.ServiceRegistration;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.service.scopes.AbstractPluginServiceRegistry;
import org.gradle.internal.state.ManagedFactoryRegistry;
import org.gradle.internal.work.AsyncWorkTracker;
import org.gradle.internal.work.ConditionalExecutionQueueFactory;
import org.gradle.internal.work.DefaultConditionalExecutionQueueFactory;
import org.gradle.internal.work.WorkerLeaseRegistry;
import org.gradle.internal.work.WorkerLeaseService;
import org.gradle.process.internal.JavaForkOptionsFactory;
import org.gradle.process.internal.health.memory.MemoryManager;
import org.gradle.process.internal.health.memory.OsMemoryInfo;
import org.gradle.process.internal.worker.WorkerProcessFactory;
import org.gradle.process.internal.worker.child.DefaultWorkerDirectoryProvider;
import org.gradle.process.internal.worker.child.WorkerDirectoryProvider;
import org.gradle.workers.WorkerExecutor;

public class WorkersServices extends AbstractPluginServiceRegistry {
    @Override
    public void registerGradleUserHomeServices(ServiceRegistration registration) {
        registration.addProvider(new GradleUserHomeServices());
    }

    @Override
    public void registerBuildSessionServices(ServiceRegistration registration) {
        registration.addProvider(new BuildSessionScopeServices());
    }

    @Override
    public void registerProjectServices(ServiceRegistration registration) {
        registration.addProvider(new ProjectScopeServices());
        registration.add(IsolatedClassloaderWorkerFactory.class);
    }

    @SuppressWarnings("unused") // Used by reflection
    private static class BuildSessionScopeServices {
        WorkerDirectoryProvider createWorkerDirectoryProvider(GradleUserHomeDirProvider gradleUserHomeDirProvider) {
            return new DefaultWorkerDirectoryProvider(gradleUserHomeDirProvider);
        }

        ConditionalExecutionQueueFactory createConditionalExecutionQueueFactory(ExecutorFactory executorFactory, WorkerLimits workerLimits, WorkerLeaseService workerLeaseService) {
            return new DefaultConditionalExecutionQueueFactory(workerLimits, executorFactory, workerLeaseService);
        }

        WorkerExecutionQueueFactory createWorkerExecutionQueueFactory(ConditionalExecutionQueueFactory conditionalExecutionQueueFactory) {
            return new WorkerExecutionQueueFactory(conditionalExecutionQueueFactory);
        }
    }

    @SuppressWarnings("unused") // Used by reflection
    private static class GradleUserHomeServices {
        WorkerDaemonClientsManager createWorkerDaemonClientsManager(WorkerProcessFactory workerFactory,
                                                                    LoggingManagerInternal loggingManager,
                                                                    ListenerManager listenerManager,
                                                                    MemoryManager memoryManager,
                                                                    OsMemoryInfo memoryInfo,
                                                                    ClassPathRegistry classPathRegistry,
                                                                    ActionExecutionSpecFactory actionExecutionSpecFactory) {
            return new WorkerDaemonClientsManager(new WorkerDaemonStarter(workerFactory, loggingManager, classPathRegistry, actionExecutionSpecFactory), listenerManager, loggingManager, memoryManager, memoryInfo);
        }

        ClassLoaderStructureProvider createClassLoaderStructureProvider(ClassLoaderRegistry classLoaderRegistry) {
            return new ClassLoaderStructureProvider(classLoaderRegistry);
        }

        IsolatableSerializerRegistry createIsolatableSerializerRegistry(ClassLoaderHierarchyHasher classLoaderHierarchyHasher, ManagedFactoryRegistry managedFactoryRegistry) {
            return new IsolatableSerializerRegistry(classLoaderHierarchyHasher, managedFactoryRegistry);
        }

        ActionExecutionSpecFactory createActionExecutionSpecFactory(IsolatableFactory isolatableFactory, IsolatableSerializerRegistry serializerRegistry) {
            return new DefaultActionExecutionSpecFactory(isolatableFactory, serializerRegistry);
        }
    }

    @SuppressWarnings("unused") // Used by reflection
    private static class ProjectScopeServices {
        WorkerExecutor createWorkerExecutor(InstantiatorFactory instantiatorFactory,
                                            WorkerDaemonFactory daemonWorkerFactory,
                                            IsolatedClassloaderWorkerFactory isolatedClassloaderWorkerFactory,
                                            JavaForkOptionsFactory forkOptionsFactory,
                                            WorkerLeaseRegistry workerLeaseRegistry,
                                            BuildOperationRunner buildOperationRunner,
                                            AsyncWorkTracker asyncWorkTracker,
                                            WorkerDirectoryProvider workerDirectoryProvider,
                                            ClassLoaderStructureProvider classLoaderStructureProvider,
                                            WorkerExecutionQueueFactory workerExecutionQueueFactory,
                                            ServiceRegistry projectServices,
                                            ActionExecutionSpecFactory actionExecutionSpecFactory,
                                            CachedClasspathTransformer classpathTransformer,
                                            ProjectLayout projectLayout,
                                            ProjectCacheDir projectCacheDir
                                            ) {
            NoIsolationWorkerFactory noIsolationWorkerFactory = new NoIsolationWorkerFactory(buildOperationRunner, instantiatorFactory, actionExecutionSpecFactory, projectServices);

            DefaultWorkerExecutor workerExecutor = instantiatorFactory.decorateLenient().newInstance(
                DefaultWorkerExecutor.class,
                daemonWorkerFactory,
                isolatedClassloaderWorkerFactory,
                noIsolationWorkerFactory,
                forkOptionsFactory,
                workerLeaseRegistry,
                buildOperationRunner,
                asyncWorkTracker,
                workerDirectoryProvider,
                workerExecutionQueueFactory,
                classLoaderStructureProvider,
                actionExecutionSpecFactory,
                instantiatorFactory.decorateLenient(projectServices),
                classpathTransformer,
                projectLayout.getProjectDirectory().getAsFile(),
                projectCacheDir);
            noIsolationWorkerFactory.setWorkerExecutor(workerExecutor);
            return workerExecutor;
        }

        WorkerDaemonFactory createWorkerDaemonFactory(WorkerDaemonClientsManager workerDaemonClientsManager, BuildOperationRunner buildOperationRunner) {
            return new WorkerDaemonFactory(workerDaemonClientsManager, buildOperationRunner);
        }
    }
}
