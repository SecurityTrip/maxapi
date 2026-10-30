package ru.securitytrip.maxapi.spring

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Lets Spring MVC controllers of a mini app backend take [MaxWebAppUser] parameters, in servlet web
 * applications with `max.bot.token` set. `max.bot.web-app` configures where `initData` is read from
 * (see [MaxBotProperties.WebApp]). Define your own [MaxWebAppArgumentResolver] bean to replace the default one.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(WebMvcConfigurer::class)
@ConditionalOnProperty(prefix = "max.bot", name = ["token"])
@EnableConfigurationProperties(MaxBotProperties::class)
public class MaxWebAppAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public fun maxWebAppArgumentResolver(properties: MaxBotProperties): MaxWebAppArgumentResolver =
        MaxWebAppArgumentResolver(properties.token, properties.webApp)

    /** Registers [resolver] with Spring MVC. */
    @Bean
    public fun maxWebAppMvcConfigurer(resolver: MaxWebAppArgumentResolver): WebMvcConfigurer = object : WebMvcConfigurer {
        override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
            resolvers += resolver
        }
    }
}
