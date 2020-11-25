#!groovy
docker_hub_credentials_id = 'dockerHubCred'
docker_hub_url = 'https://index.docker.io/v1/'
kubernetes_credentials_id = 'KubeCred'
kubernetes_url = 'https://kubernetes.default:443'
sonarqube_credentials_id = 'sonarCred'

colorBlue = '#0000FF'
colorGreen = '#00FF00'
colorRed = '#FF0000'

def mavenSpingBootBuild(configs) {

    checkOutSCM(params)
    mavenUnitTests(params)
    mavenIntegrationTests(params)
    mavenPublishTest(params)
    mavenBuild(params)
    sonarQualityAnalysis(params)
    owsapDependancyCheck(params)
    dockerImage = dockerize(params)
    pushDockerImageToRepo(dockerImage, configs)
    deployToArtifactory(configs)    
    deployToKubernetes(configs)
}

def deployableBranch(branch) {
    return (branch == "master") || (branch == "develop") || (branch =~ /^release\/.*/) || deployableBranch
}

def defaultConfigs(configs) {
    setDefault(configs, "branch_checkout_dir", 'service')
    setDefault(configs, "service", 'micro-service')
    setDefault(configs, "branch", 'develop')
    setDefault(configs, "sonarqube_credentials_id", sonarqube_credentials_id)
    setDefault(configs, "docker_hub_credentials_id", docker_hub_credentials_id)
    setDefault(configs, "docker_hub_url", docker_hub_url)
    setDefault(configs, "kubernetes_credentials_id", kubernetes_credentials_id)
    setDefault(configs, "aws_credentials_id", 'awsJenkinsUserCred')
    setDefault(configs, "kubeDeploymentFile", './infra/k8s-deployment.yaml')
    setDefault(configs, "kubeServiceFile", './infra/k8s-service.yaml')
    setDefault(configs, "jenkins_slack_channel", 'jenkins')
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

    stage("Unit Test") {
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
    stage("OWASP Security Check"){
        if (configs.get('skip_owasp', false)) {
            echo "skiping SonarQube"
            return
        }

        dir(configs.branch_checkout_dir) {
            dependencyCheck additionalArguments: '', odcInstallation: 'owasp'
            dependencyCheckPublisher pattern: 'dependency-check-report.xml'
        }
    }
}

def dockerize(configs) {

    stage('Build docker image') {
        echo "Build Docker Image!!!"
        dir(configs.branch_checkout_dir) {
            def customImage = docker.build(configs.dockerRepoName + "/" + configs.dockerImageName)

            configs.put("dockerImage", customImage)

            currentBuild.displayName = "#" + (currentBuild.number + "-${configs.git_commit_id}")

            return customImage
        }
    }
}

def pushDockerImageToRepo(customImage, configs) {
    stage('Push Docker Image to Repo') {
        if (configs.get('skip_docker_push', false)) {
            echo "skip push docker image to docker repo"
            return
        }

        // This step should not normally be used in your script. Consult the inline help for details.
        withDockerRegistry(credentialsId: docker_hub_credentials_id, url: docker_hub_url) {
            customImage.push("${configs.git_commit_id}")
            customImage.push("latest")
        }
    }
}

def deployToArtifactory(configs) {
    stage('Push to artifactory') {
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
}

def deployToKubernetes(configs) {

    stage("Deploy to Dev Environment") {

        if (configs.get('skip_kubernetes_deployment', false)) {
            echo "skip kubernetes deployment!!!"
            return
        }
                    
        dir(configs.branch_checkout_dir) {
            //withKubeConfig(credentialsId: kubernetes_credentials_id, serverUrl: kubernetes_url) {

                sh "sed -i 's|IMAGE|${configs.dockerRepoName}/${configs.dockerImageName}:${configs.git_commit_id}|g' ${configs.kubeDeploymentFile}"

                sh "kubectl apply -f ${configs.kubeDeploymentFile}"
                sh "kubectl apply -f ${configs.kubeServiceFile}"

                //sh "kubectl get pods"
                //sh "kubectl get svc"
            //}
        }
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
    dockerImage = dockerize(params)
    pushDockerImageToRepo(dockerImage, configs)
    deployToKubernetes(configs)
}

def pythonUnitTests(configs) {
    stage("Unit Test") {
        dir(configs.branch_checkout_dir) {
            sh "pip3 install -r requirements.txt"
        }
    }
}

//////////////////////////////////////
/////////// Angular Build ////////////
//////////////////////////////////////

def angularBuildAndDeploy(configs) {
    checkOutSCM(params)
    installNodeModules(params)
    angularUnitTests(params)
    angularPublishTest(params)
    angularLint(params)
    angularSonarQualityAnalysis(params)
    angularBuild(params)
    dockerImage = dockerize(params)
    pushDockerImageToRepo(dockerImage, configs)  
    deployToKubernetes(configs)
}

def installNodeModules(configs) {
    stage ('Install Node Modules'){
        dir(configs.branch_checkout_dir) {
            sh '''
            npm install --verbose -d 
            npm install --save classlist.js
            '''
        }
    }
}

def angularUnitTests(configs) {
    stage("Unit Test") {
        if (configs.get('skip_unit_test', false)) {
            echo "skiping unit testing"
            return
        }

        dir(configs.branch_checkout_dir) {
            sh "npm run test-headless"
            sh "npm run code-coverage"
        }
    }
}

def angularPublishTest(configs) {

    stage('Publish Result') {
        if (configs.get('skip_unit_test', false)) {
            echo "skiping publish result"
            return
        }

        dir(configs.branch_checkout_dir) {
            junit "test-results.xml"
            //junit(allowEmptyResults: true, testResults: "./test-results.xml")
        }
    }
}

def angularSonarQualityAnalysis(configs) {

    stage('SonarQube analysis') {
        if (configs.get('skip_sonar', false)) {
            echo "skiping SonarQube"
            return
        }
        
        dir(configs.branch_checkout_dir) {
            echo "SonarQube Code Quality Analysis!!!"
            withSonarQubeEnv('SonarQube') {    
                sh "npm run sonar"
            }
        }
    }
}

def angularLint(configs) {
    stage('Angular Lint Check') {
        dir(configs.branch_checkout_dir) {
            sh 'npm run lint'
        }
    }
}

def angularBuild(configs) {
    stage('Angular Build') {
        dir(configs.branch_checkout_dir) {
            sh 'npm run build'
        }
    }
}

////////////////////////////////////////////////
/////////// Send Slack Notification ////////////
////////////////////////////////////////////////
def isBackToNormal() {
    return currentBuild?.previousBuild?.result != 'SUCCESS' && env.BUILD_NUMBER != 1
}

def sendSlack(configs) {
    if (configs.get('skip_notification', false)) {
        echo "skip Slack Notification!!!"
        return
    }

    if (currentBuild.result != 'FAILURE') {
        currentBuild.result = 'SUCCESS'
        if (isBackToNormal()) {
            sendToSlack(configs, colorBlue, "BACK TO NORMAL", configs.service, configs.jenkins_slack_channel, configs.branch)
        } else {
            sendToSlack(configs, colorGreen, "SUCCESS", configs.service, configs.jenkins_slack_channel, configs.branch)
        }
    } else if (currentBuild.result == 'FAILURE') {
        sendToSlack(configs, colorRed, "FAILURE", configs.service, configs.jenkins_slack_channel, configs.branch)
    }
}

def sendToSlack(configs, color, status, service, channel, branch) {

    currentBuild.displayName = "#" + (currentBuild.number + "-${configs.git_commit_id}-" + currentBuild.result)

    slackSend(
            color: color,
            channel: channel,
            message: "Status: ${status} " +
                    "(<${env.BUILD_URL}|Open>)\n" +
                    "Service: `${service}`\n" +
                    "Branch: `${branch}`\n" +
                    "Build number: `#${env.BUILD_NUMBER}`\n"
    )
}

return this
