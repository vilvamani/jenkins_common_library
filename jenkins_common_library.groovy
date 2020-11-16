#!groovy

def deployableBranch(branch) {
    return (branch == "master") || (branch == "develop") || (branch =~ /^release\/.*/) || deployableBranch
}

def defaultConfigs(configs) {
    setDefault(configs, "k8s_credentials_id", 'kubeCred')
    setDefault(configs, "sonarqube_credentials_id", 'sonarCred')
    setDefault(configs, "dockerRegistry_credentials_id", 'dcokerRegistryCred')
    setDefault(configs, "dockerRegistry_url", 'dcokerRegistryURL')
    setDefault(configs, "aws_credentials_id", 'awsJenkinsUserCred')
}

def setDefault(configs, key, default_value) {
    if (configs.get(key) == null) {
        configs.put(key, default_value)
    }
}

def checkOutSCM(configs) {
    stage('CheckoutBranch') {
        dir(configs.branch_checkout_dir) {
            echo "Git Checkout SCM!!!"
            git(url: configs.repo_url, branch:  configs.branch)
        }
    }

    getCommitId(configs)
}

def getCommitId(configs) {
    stage("Read Author Details") {
        dir(configs.branch_checkout_dir) {
            git_commit_id = sh label: 'get last commit', returnStdout: true, script: 'git rev-parse --short HEAD~0'
            configs.put("git_commit_id", git_commit_id)
        }
    }
}

def mavenUnitTests(configs) {
    stage('UnitTest') {
        if (configs.get('unittest_skip', false)) {
            echo "skiping unit testing"
            return
        }

        dir(configs.branch_checkout_dir) {
            echo "Maven Unit test!!!"

            configFileProvider([configFile(fileId: '8b36a983-2cd4-4843-956f-f2f5f72efff4', variable: 'MAVEN_SETTINGS')]) {
                sh "mvn -s $MAVEN_SETTINGS clean test -U"
            }
        }
    }
}

def mavenIntegrationTests(configs) {
    stage('IntegrationTest') {
        if (configs.get('integrationtest_skip', false)) {
            echo "skiping integration testing"
            return
        }

        dir(configs.branch_checkout_dir) {
            echo "Maven Integration test!!!"

            configFileProvider([configFile(fileId: '8b36a983-2cd4-4843-956f-f2f5f72efff4', variable: 'MAVEN_SETTINGS')]) {
                sh "mvn -s $MAVEN_SETTINGS test -Dtest=**IT"
            }
        }
    }
}

def sonarQualityAnalysis(configs) {
    stage('SonarQube analysis') {
        if (configs.get('sonar_skip', false)) {
            echo "skiping SonarQube"
            return
        }
        
        dir(configs.branch_checkout_dir) {
            echo "SonarQube Code Quality Analysis!!!"

            configFileProvider([configFile(fileId: '8b36a983-2cd4-4843-956f-f2f5f72efff4', variable: 'MAVEN_SETTINGS')]) {
                sh "mvn -s $MAVEN_SETTINGS verify sonar:sonar"
            }
        }
    }
}

return this