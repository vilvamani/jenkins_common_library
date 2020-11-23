#!groovy
docker_hub_credentials_id = 'dockerHubCred'
docker_hub_url = 'https://index.docker.io/v1/'
kubernetes_credentials_id = 'KubeCred'
kubernetes_url = 'https://kubernetes.default:443'
sonarqube_credentials_id = 'sonarCred'


def mavenSpingBootBuild(configs) {

    checkOutSCM(params)
    mavenUnitTests(params)
    mavenIntegrationTests(params)
    mavenPublishTest(params)
    mavenBuild(params)
    sonarQualityAnalysis(params)
    owsapDependancyCheck(params)
    dockerImage = jenkinsLibrary.dockerize(params)

    stage('Push to artifactory') {
        deployToArtifactory(configs)
    }

    stage('Push Docker Image to Repo') {
        pushDockerImageToRepo(dockerImage, configs)
    }
    
    stage("K8s Deployment") {
        deployToKubernetes(configs)
    }

    updateGithubCommitStatus(BUILD_STATUS)

}

def deployableBranch(branch) {
    return (branch == "master") || (branch == "develop") || (branch =~ /^release\/.*/) || deployableBranch
}

def defaultConfigs(configs) {
    setDefault(configs, "branch_checkout_dir", 'service')
    setDefault(configs, "branch", 'develop')
    setDefault(configs, "sonarqube_credentials_id", sonarqube_credentials_id)
    setDefault(configs, "docker_hub_credentials_id", docker_hub_credentials_id)
    setDefault(configs, "docker_hub_url", docker_hub_url)
    setDefault(configs, "kubernetes_credentials_id", kubernetes_credentials_id)
    setDefault(configs, "aws_credentials_id", 'awsJenkinsUserCred')
    setDefault(configs, "kubeDeploymentFile", './infra/k8s-deployment.yaml')
    setDefault(configs, "kubeServiceFile", './infra/k8s-service.yaml')
}

def setDefault(configs, key, default_value) {
    if (configs.get(key) == null) {
        configs.put(key, default_value)
    }
}

def checkOutSCM(configs) {
    
    stage('Checkout Branch') {
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
            configs.put("git_commit_id", git_commit_id.trim())
        }
    }
}

def mavenUnitTests(configs) {

    stage('Unit Test') {
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

    stage('Integration Test') {
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

def owsapDependancyCheck(configs) {
    stage("OWASP Dependancy Check"){
        dependencyCheck additionalArguments: '', odcInstallation: 'owasp'
        dependencyCheckPublisher pattern: 'dependency-check-report.xml'
    }
}

def dockerize(configs) {

    stage('Build docker image') {
        echo "Build Docker Image!!!"
        dir(configs.branch_checkout_dir) {
            def customImage = docker.build(configs.dockerRepoName + "/" + configs.dockerImageName)

            configs.put("dockerImage", customImage)

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

def deployToKubernetes(configs) {

    if (configs.get('skip_kubernetes_deployment', false)) {
        echo "skip kubernetes deployment!!!"
        return
    }
                
    dir(configs.branch_checkout_dir) {
        //withKubeConfig(credentialsId: kubernetes_credentials_id, serverUrl: kubernetes_url) {

            sh "sed -i 's|DOCKER_IMAGE|${configs.dockerRepoName}/${configs.dockerImageName}:${configs.git_commit_id}|g' ${configs.kubeDeploymentFile}"

            sh "kubectl apply -f ${configs.kubeDeploymentFile}"
            sh "kubectl apply -f ${configs.kubeServiceFile}"

            sh "kubectl get pods"
            sh "kubectl get svc"
        //}
    }
}

/////////////////////////////////////
/////////// Python Build ////////////
/////////////////////////////////////

def pythonFlaskBuild(configs) {

    checkOutSCM(params)
    pythonUnitTests(params)
    sonarQualityAnalysis(params)
    owsapDependancyCheck(params)
    dockerImage = jenkinsLibrary.dockerize(params)

    stage('Push Docker Image to Repo') {
        pushDockerImageToRepo(dockerImage, configs)
    }
}

def pythonUnitTests(configs) {
    stage("Python UnitTest") {
        dir(configs.branch_checkout_dir) {
            sh "pip3 install -r requirements.txt"
        }
    }
}

def getRepoURL() {
    dir('service') {
        sh "git config --get remote.origin.url > .git/remote-url"
        return readFile(".git/remote-url").trim()
    }
}

def getCommitSha() {
    dir('service') {
        sh "git rev-parse HEAD > .git/current-commit"
        return readFile(".git/current-commit").trim()
    }
}

def updateGithubCommitStatus(build) {
  // workaround https://issues.jenkins-ci.org/browse/JENKINS-38674
  repoUrl = getRepoURL()
  commitSha = getCommitSha()

  step([
    $class: 'GitHubCommitStatusSetter',
    reposSource: [$class: "ManuallyEnteredRepositorySource", url: repoUrl],
    commitShaSource: [$class: "ManuallyEnteredShaSource", sha: commitSha],
    errorHandlers: [[$class: 'ShallowAnyErrorHandler']],
    statusResultSource: [
      $class: 'ConditionalStatusResultSource',
      results: [
        [$class: 'BetterThanOrEqualBuildResult', result: 'SUCCESS', state: 'SUCCESS', message: build.description],
        [$class: 'BetterThanOrEqualBuildResult', result: 'FAILURE', state: 'FAILURE', message: build.description],
        [$class: 'AnyBuildResult', state: 'FAILURE', message: 'Loophole']
      ]
    ]
  ])
}

return this
