package com.equadis.portal;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Keeps the layers honest: controller -> service -> repository. These rules fail the build, so
 * the architecture stays as it is documented without relying on anyone's memory.
 */
@AnalyzeClasses(packages = "com.equadis.portal", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule controllersDoNotCallRepositories = noClasses()
            .that().haveSimpleNameEndingWith("Controller")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository");

    @ArchTest
    static final ArchRule onlyRepositoriesContainSql = noClasses()
            .that().haveSimpleNameNotEndingWith("Repository")
            .should().dependOnClassesThat().areAssignableTo(JdbcClient.class)
            .orShould().dependOnClassesThat().areAssignableTo(NamedParameterJdbcTemplate.class)
            .orShould().dependOnClassesThat().areAssignableTo(JdbcTemplate.class);

    @ArchTest
    static final ArchRule servicesDoNotCallControllers = noClasses()
            .that().areAnnotatedWith(Service.class)
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Controller");

    @ArchTest
    static final ArchRule repositoriesDoNotCallServices = noClasses()
            .that().areAnnotatedWith(Repository.class)
            .should().dependOnClassesThat().areAnnotatedWith(Service.class);
}
