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
      url: "git@github.com:viovendi/${repoName}.git"
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