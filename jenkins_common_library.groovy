#!groovy
docker_hub_credentials_id = 'dockerHubCred'
docker_hub_url = 'https://index.docker.io/v1/'

def pushToRepositories(customImage, configs) {
    stage('Push to artifactory') {
        deployToArtifactory(configs)
    }

    stage('Push Docker Image to Repo') {
        pushDockerImageToRepo(customImage, configs)
    }
}

def deployableBranch(branch) {
    return (branch == "master") || (branch == "develop") || (branch =~ /^release\/.*/) || deployableBranch
}

def defaultConfigs(configs) {
    setDefault(configs, "branch_checkout_dir", 'kubeCred')
    setDefault(configs, "branch", 'develop')
    setDefault(configs, "sonarqube_credentials_id", 'sonarCred')
    setDefault(configs, "docker_hub_credentials_id", 'dockerHubCred')
    setDefault(configs, "docker_hub_url", 'https://index.docker.io/v1/')
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
        if (configs.get('skip_unit_test', false)) {
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

def mavenPublishTest(configs) {

    stage('Publish Result') {
        if (configs.get('skip_unit_test', false)) {
            echo "skiping publish result"
            return
        }

        dir(configs.branch_checkout_dir) {
            junit(allowEmptyResults: true, testResults: '**/target/surefire-reports/*.xml,' +'**/target/failsafe-reports/*.xml')
            jacoco()
        }
    }
}

def mavenBuild(configs) {

    stage('Build') {
        dir(configs.branch_checkout_dir) {
            echo "Maven Build!!!"

            configFileProvider([configFile(fileId: '8b36a983-2cd4-4843-956f-f2f5f72efff4', variable: 'MAVEN_SETTINGS')]) {
                sh "mvn -s $MAVEN_SETTINGS package -DskipTests -U -Dmaven.repo.local=$WORKSPACE"
            }
        }
    }
}

def mavenIntegrationTests(configs) {

    stage('IntegrationTest') {
        if (configs.get('skip_integration_test', false)) {
            echo "skiping integration testing"
            return
        }

        dir(configs.branch_checkout_dir) {
            echo "Maven Integration test!!!"

            configFileProvider([configFile(fileId: '8b36a983-2cd4-4843-956f-f2f5f72efff4', variable: 'MAVEN_SETTINGS')]) {
                sh "mvn -s $MAVEN_SETTINGS test -Dtest=*Test,*IT test"
            }
        }
    }
}

def sonarQualityAnalysis(configs) {

    stage('SonarQube analysis') {
        if (configs.get('skip_sonar', false)) {
            echo "skiping SonarQube"
            return
        }
        
        dir(configs.branch_checkout_dir) {
            echo "SonarQube Code Quality Analysis!!!"
            withSonarQubeEnv('SonarQube') {    
                configFileProvider([configFile(fileId: '8b36a983-2cd4-4843-956f-f2f5f72efff4', variable: 'MAVEN_SETTINGS')]) {
                    sh "mvn -s $MAVEN_SETTINGS verify sonar:sonar"
                }
            }
        }
    }
}

def dockerize(configs) {

    stage('Build docker image') {
        echo "Build Docker Image!!!"
        dir(configs.branch_checkout_dir) {
            def customImage = docker.build(configs.dockerRepoName + "/" + configs.dockerImageName)

            return customImage
        }
    }
}

def pushDockerImageToRepo(customImage, configs) {

    if (configs.get('skip_docker_push', false)) {
        echo "skip push docker image to docker repo"
        return
    }

    // This step should not normally be used in your script. Consult the inline help for details.
    withDockerRegistry(credentialsId: docker_hub_credentials_id, url: docker_hub_url) {
        customImage.push("${configs.git_commit_id}")
        customImage.push("latest")
    }

    // Remove dangling Docker images
    sh "docker image prune --all --force"
}

def deployToArtifactory(configs) {

    if (configs.get('skip_artifactory', false)) {
        echo "skip deploy app to artifactory"
        return
    }

    dir(configs.branch_checkout_dir) {
        echo "Deploy app to artifactory!!!"
        configFileProvider([configFile(fileId: '8b36a983-2cd4-4843-956f-f2f5f72efff4', variable: 'MAVEN_SETTINGS')]) {
            sh "mvn -s $MAVEN_SETTINGS clean deploy"
        }
    }
}

return this