/*
 * Copyright 2024 T Jake Luciani
 *
 * The Jlama Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.github.tjake.jlama.cli.commands;

import com.github.tjake.jlama.net.Coordinator;
import com.github.tjake.jlama.net.Worker;
import com.github.tjake.jlama.safetensors.DType;
import com.github.tjake.jlama.util.PhysicalCoreExecutor;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.Optional;

@CommandLine.Command(name = "cluster-coordinator", description = "Starts a distributed rest api for a model using cluster workers", abbreviateSynopsis = true)
@SpringBootApplication(scanBasePackages = { "com.github.tjake.jlama.net.openai", "com.github.tjake.jlama.cli.commands", "com.github.tjake.jlama.net.grpc" })
@SpringBootConfiguration
@Configuration
public class ClusterCoordinatorCommand extends ModelBaseCommand implements WebMvcConfigurer {

    @CommandLine.Option(names = {
        "--worker-count" }, paramLabel = "ARG", description = "signifies this instance is a coordinator", required = true)
    int workerCount = 1;

    @CommandLine.Option(names = {
            "--split-heads" }, paramLabel = "ARG", description = "Should coordinator split work across attention heads (default: ${DEFAULT-VALUE})", required = true)
    boolean splitHeads = true;

    @CommandLine.Option(names = {
            "--split-layers" }, paramLabel = "ARG", description = "Should coordinator split work across layers (default: ${DEFAULT-VALUE})", required = false)
    boolean splitLayers = false;

    @CommandLine.Option(names = {
        "--grpc-port" }, paramLabel = "ARG", description = "grpc port to listen on (default: ${DEFAULT-VALUE})", defaultValue = "9777")
    int grpcPort = 9777;

    @CommandLine.Option(names = {
        "--port" }, paramLabel = "ARG", description = "http port to listen on (default: ${DEFAULT-VALUE})", defaultValue = "8080")
    int port = 8080;

    @CommandLine.Option(names = {
        "--model-type" }, paramLabel = "ARG", description = "The models base type F32/BF16 (default: ${DEFAULT-VALUE})", defaultValue = "F32")
    DType modelType = DType.F32;

    @CommandLine.Option(names = {
        "--include-worker" }, paramLabel = "ARG", description = "Start a worker in the same jvm (default: ${DEFAULT-VALUE})", defaultValue = "true")
    Boolean includeWorker = false;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/admin/**").addResourceLocations("classpath:/static/admin/");
        registry.addResourceHandler("/ui/**").addResourceLocations("classpath:/static/ui/");
    }

    @Override
    public void run() {
        try {

            if (this.advancedSection.threadCount != null) {
                PhysicalCoreExecutor.overrideThreadCount(this.advancedSection.threadCount);
            }

            // Download the model metadata
            Path model = SimpleBaseCommand.getModel(
                modelName,
                modelDirectory,
                true,
                downloadSection.branch,
                downloadSection.authToken,
                false
            );

            Coordinator c = new Coordinator(
                model.toFile(),
                SimpleBaseCommand.getOwner(modelName),
                SimpleBaseCommand.getName(modelName),
                modelType,
                workingDirectory,
                grpcPort,
                workerCount,
                splitHeads,
                splitLayers,
                Optional.ofNullable(downloadSection.authToken),
                Optional.ofNullable(downloadSection.branch)
            );

            // This wires up the bean for the rest api
            ApiServiceCommand.m = c;

            new Thread(() -> {
                try {
                    c.start();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();

            if (includeWorker) {
                Worker w = new Worker(
                        model.toFile(),
                        SimpleBaseCommand.getOwner(modelName),
                        SimpleBaseCommand.getName(modelName),
                        modelType,
                        "localhost",
                        grpcPort,
                        grpcPort + 1,
                        workingDirectory,
                        advancedSection.workingMemoryType,
                        advancedSection.workingQuantizationType,
                        Optional.ofNullable(advancedSection.modelQuantization),
                        Optional.ofNullable("in-jvm-worker"),
                        Optional.ofNullable(downloadSection.authToken),
                        Optional.ofNullable(downloadSection.branch));

                new Thread(() -> {
                            try {
                                w.run();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }).start();
            }

            System.out.println("Chat UI: http://localhost:" + port);
            System.out.println("OpenAI Chat API: http://localhost:" + port + "/chat/completions");

            new SpringApplicationBuilder(ClusterCoordinatorCommand.class).lazyInitialization(true)
                .properties("server.port", "" + port, "logging.level.org.springframework.web", "info")
                .build()
                .run();

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(2);
        }
    }
}
