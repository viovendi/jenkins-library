def isProduction() {
  assert params.INFRASTRUCTURE != null

  return params.INFRASTRUCTURE ==~ /production\d/
}

def isStaging() {
  assert params.INFRASTRUCTURE != null

  return params.INFRASTRUCTURE ==~ /staging\d/
}

def isCreateOrUpdateAction() {
  assert params.ACTION != null

  return params.ACTION == 'Create/Update'
}

def isCreateAction() {
  assert params.ACTION != null

  return params.ACTION == 'Create'
}

def isDeleteAction() {
  assert params.ACTION != null

  return params.ACTION == 'Delete'
}

// Requires AWS credentials
def saveParameterToDynamoDb(jobName, infrastructure, parameterName, value) {
  assert jobName != null
  assert infrastructure != null
  assert parameterName != null
  assert value != null

  parameter = "${jobName}/${infrastructure}/${parameterName}"
  sh (
    script: /aws dynamodb put-item --table-name jenkins-last-successful-build-parameters --item '{"parameter": {"S": "${parameter}"}, "value": {"S": "${value}"}}'/
  )
}

def updateEnvironmentTable(infrastructure, project, version) {
  build (
    job: 'Tools/Update Environment Table',
    parameters: [
      string (
        name: 'INFRASTRUCTURE',
        value: infrastructure
      ),
      string (
        name: 'PROJECT',
        value: project
      ),
      string (
        name: 'VERSION',
        value: version
      )
    ]
  )
}

// Requires AWS credentials
def getParameterFromDynamoDb(jobName, infrastructure, parameterName) {
  assert jobName != null
  assert infrastructure != null
  assert parameterName != null

  parameter = "${jobName}/${infrastructure}/${parameterName}"
  value = sh (
    script: /aws dynamodb get-item --table-name jenkins-last-successful-build-parameters --key '{"parameter": {"S": "${parameter}"}}' --query "Item.value.S" --output text/,
    returnStdout: true
  ).trim()

  return value
}

def getProductionApproval() {
  message1Slack = "@here Production changes! Approval Required!\n" +
  "${env.JOB_NAME} (<${env.BUILD_URL}|#${env.BUILD_NUMBER}>) has been paused for approval.\n"

  message1Console = "\n\nProduction changes! Approval Required!\n" +
  "The build has been paused for approval.\n"

  message2 = describeParameters(params) +
  "Proceed the build to approve production changes or abort it to cancel.\n" + 
  "If no action is made the build will be aborted in 3 hours automatically.\n"

  message3Slack = "(<${env.BUILD_URL}/input/|Approve/Abort>)"

  slackSend(
    color: 'warning', 
    message: message1Slack + message2 + message3Slack
  )

  timeout(time: 3, unit: 'HOURS') {
    input message1Console + message2
  }
}

def sendSlackAlertForTests(state) {
  assert state != null
  //TODO add asserts

  slackSend (
    channel: "#notifications_tests",
    color: getColor(state), 
    message: "${env.JOB_NAME} (<${env.BUILD_URL}|#${env.BUILD_NUMBER}>) ${state} after ${currentBuild.durationString}\n"
  )
}

def sendSlackAlert(state) {
  assert state != null
  //TODO add asserts

  slackSend (
    color: getColor(state), 
    message: "${env.JOB_NAME} (<${env.BUILD_URL}|#${env.BUILD_NUMBER}>) ${state} after ${currentBuild.durationString}\n" + 
    "\nParameters: \n" +
    describeParameters(params)
  )
}

def sendSlackAlertWithOutput(state, outputValues) {
  assert state != null
  assert outputValues != null
  //TODO add asserts

  slackSend (
    color: getColor(state), 
    message: "${env.JOB_NAME} (<${env.BUILD_URL}|#${env.BUILD_NUMBER}>) ${state} after ${currentBuild.durationString}\n" + 
    "\nParameters: \n" +
    describeParameters(params) +
    "\nOutputs: \n" +
    describeParameters(outputValues)
  )
}

def getColor(state) {
  assert state != null

  def color

  switch (state) {
    case "success":
      color = "good"
      break
    case "failure":
      color = "danger"
      break
    case "aborted":
      color = "#D4DADF"
      break
    default:
      color = "#CCCCCC"
      break
  }

  return color
}

def checkoutFromGithub(repoName, branchName, credentialsId) {
  assert repoName != null
  assert branchName != null
  assert credentialsId != null

  checkout([
    $class: 'GitSCM', 
    branches: [[
      name: "*/${branchName}"
    ]], 
    browser: [
      $class: 'GithubWeb', 
      repoUrl: "https://github.com/viovendi/${repoName}/"
    ], 
    doGenerateSubmoduleConfigurations: false, 
    extensions: [], 
    submoduleCfg: [], 
    userRemoteConfigs: [[
      credentialsId: credentialsId, 
      url: "https://github.com/viovendi/${repoName}"
    ]]
  ])
}

// Requires AWS credentials
def deleteCloudFormationStack(stackName) {
  assert stackName != null

  try {
    sh "aws cloudformation delete-stack --stack-name ${stackName}"
    sh "aws cloudformation wait stack-delete-complete --stack-name ${stackName}"
  }
  catch(e) {
    describeCloudFormationStack(stackName)
    throw e;
  }
}

def describeParameters(arr) {
  assert arr != null

  resultStr = ""
  arr.each {
    resultStr = resultStr + "- ${it}\n"
  }
  resultStr = resultStr + "\n"
  return resultStr
}

// Requires AWS credentials
def executeWithOptionalCloudFormationChangesetApproval(cmdStr) {
  assert cmdStr != null
  assert params.CHANGESET_MANUAL_APPROVAL != null

  if(params.CHANGESET_MANUAL_APPROVAL) {
    cmdStr = cmdStr + " --no-execute-changeset"

    sh cmdStr

    timeout(time: 3, unit: 'HOURS') {
      input "Approve AWS CloudFormation change set and proceed the build after the change set is applied"
    }
  }
  else {
    sh cmdStr
  }
}

// Requires AWS credentials
def describeCloudFormationStack(stackName) {
  sh "aws cloudformation describe-stack-events --stack-name ${stackName}"
}

// Requires AWS credentials
def getParameterValueFromParameterStore(parameterName) {
  value = sh(
    script: "aws ssm get-parameters --names ${parameterName} --query 'Parameters[0].Value' --output text",
    returnStdout: true
  ).trim()

  return value
}

// Requires AWS credentials
def getValueFromCloudFormationStackExport(name) {
  value = sh(
    script: "aws cloudformation list-exports --query \"Exports[?Name=='${name}'].Value\" --no-paginate --output text",
    returnStdout: true
  ).trim()

  return value
}

def waitForCommandResult(script, query, expectedValue, timeoutTime, timeoutUnit) {
  timeout(time: timeoutTime, unit: timeoutUnit) {
    waitUntil {
      def value = getValueFromResponse(script, query)
      return (value == expectedValue)
    }
  }
}

def getValueFromResponse(script, query) {
  def value = sh(
        script: "${script} --query '${query}' --no-paginate --output text",
        returnStdout: true
      ).trim()

  return value
}

def sshCommand(credentialsName, user, ipAddress, commands) {
  sshagent(credentials: ["${credentialsName}"]) {
    sh (
      "ssh -o StrictHostKeyChecking=no ${user}@${ipAddress} \"${commands}\""
    )
  }
}

def scpFolder(credentialsName, user, ipAddress, from, to) {
  sshagent(credentials: ["${credentialsName}"]) {
    sh (
      "scp -rp ${from} ${user}@${ipAddress}:${to}"
    )
  }
}

def scpFile(credentialsName, user, ipAddress, from, to) {
  sshagent(credentials: ["${credentialsName}"]) {
    sh (
      "scp -p ${from} ${user}@${ipAddress}:${to}"
    )
  }
}

def getLightsailInstanceIp(instanceName) {
  def value = getValueFromResponse(
      "aws lightsail get-instance --instance-name ${instanceName}",
      'instance.publicIpAddress'
    )
  return value
}

// Method use GitHub plugin to set status for particular commit
def setGitHubCommitStatus(String repoName, String commitHash, String state, String message) {
  step([
      $class            : 'GitHubCommitStatusSetter',
      reposSource       : [$class: 'ManuallyEnteredRepositorySource', url: "https://github.com/viovendi/$repoName"],
      contextSource     : [$class: 'ManuallyEnteredCommitContextSource', context: 'Unit test'],
      errorHandlers     : [[$class: 'ChangingBuildStatusErrorHandler', result: 'UNSTABLE']],
      commitShaSource   : [$class: 'ManuallyEnteredShaSource', sha: commitHash],
      statusResultSource: [$class: 'ConditionalStatusResultSource', results: [[$class: 'AnyBuildResult', message: message, state: state]]]
  ])
}

def addInboundRuleForSecurityGroup(String groupName, int port, String ip) {
  targetGroupId = getValueFromResponse("aws ec2 describe-security-groups --filters Name=group-name,Values=$groupName", 'SecurityGroups[*].GroupId')
  openIps = getValueFromResponse("aws ec2 describe-security-groups --group-ids $targetGroupId", 'SecurityGroups[*].IpPermissions[*].IpRanges[*].CidrIp')

  if (!openIps.contains(ip)) { // rule not found
    sh "aws ec2 authorize-security-group-ingress --group-id $targetGroupId --protocol tcp --port $port --cidr $ip/32"
    sleep(10)
  }
}

def removeInboundRuleForSecurityGroup(String groupName, int port, String ip) {
  targetGroupId = getValueFromResponse("aws ec2 describe-security-groups --filters Name=group-name,Values=$groupName", 'SecurityGroups[*].GroupId')
  sh "aws ec2 revoke-security-group-ingress --group-id $targetGroupId --protocol tcp --port $port --cidr $ip/32"
}

def getAgentPublicIp() {
  return sh(script: 'curl http://checkip.amazonaws.com', returnStdout: true).trim()
}

// Checkout with custom refspec settings for PRs
def checkoutPR(String repoName, String pullId, String commitHash, String action, String author) {
  String refsp = ''
  String cause = (pullId == 'empty' ? "push ($commitHash)" : "PR (#$pullId) ") + (action == 'empty' ? '' : action)
  currentBuild.description = "Started by $cause by $author"
  if (pullId != 'empty') {
    // change refspec and branch for PR
    refsp = '+refs/pull/*:refs/remotes/origin/pr/*'
    commitHash = "origin/pr/$pullId/merge"
  }
  checkout([$class           : 'GitSCM',
            branches         : [[name: commitHash]],
            browser          : [$class: 'GithubWeb', repoUrl: "https://github.com/viovendi/$repoName"],
            extensions       : [], submoduleCfg: [], doGenerateSubmoduleConfigurations: false,
            userRemoteConfigs: [[credentialsId: 'doobot-github', refspec: refsp, url: "https://github.com/viovendi/$repoName"]]
  ])
}

def removeZipArtifacts() {
  sh "rm -rf *.zip"
}
