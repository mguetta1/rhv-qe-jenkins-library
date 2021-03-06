import groovy.json.JsonSlurper

/**
 * Extracts the displayName from given build.
 *
 * @param response String Object containing the response from previous HTTP GET request.
 * @param parent_pipeline_status String Object indicates whether this is an parent build.
 *
 * @return displayName String represents the displayName of the build.
 */
def build_info(response, parent_pipeline_status =null) {
    def response_object = null

    try {
      def jsonSlurper = new JsonSlurper()
      response_object = jsonSlurper.parseText(response)

      if (parent_pipeline_status) {
        def description = response_object.description

        if (!description) {
          // Executed only in case of pending job, i.e. 'waiting' status
          response_object.actions[3].parameters.each {
                                                        if ("${it}".contains("TEXT_BUILD_DESCRIPTION")) {
                                                          def temp_str = "${it}".split(",")[2].split(':')[1]
                                                          description = temp_str.substring(0, temp_str.length() - 1)
                                                        }
                                                     }
        }
        return description
      }

      // Create URL towards parent build
      def url = env.JENKINS_URL \
             + response_object.actions.causes[0].upstreamUrl.join(", ") \
             + response_object.actions.causes[0].upstreamBuild.join(", ") \
             + "/api/json"

      response = url.toURL().text
      response_object = jsonSlurper.parseText(response)
    } catch (Error e){}
    return response_object.displayName
}

/**
 * Monitors and updates the status of production jobs/builds.
 *
 * @param config Map Object containing the following keys.
 *
 * Mail related arguments:
 *   config.job_parameters: Parameters of given build/job
 *   config.name: Name of the job/build
 *   config.update_status: Indicate whether the parent build is in WAITING or ABORTED status.
 */
def call(Map config = [:]) {
    ansiColor('xterm') {
    def job_parameters = config.get('job_parameters')
    def job_name = config.get('name')
    def cherry_pick = config.get('ref')
    def build_status = config.get('update_status')

    // Defining URL for REST API request
    def url = env.BUILD_URL + "api/json"
    def response = url.toURL().text

    sh "rm -rf rhevm-qe-infra \
        && git clone https://code.engineering.redhat.com/gerrit/rhevm-qe-automation/rhevm-qe-infra.git \
        && cd rhevm-qe-infra/scripts/production-monitoring"

    def build_name = build_info(response)

    // Check if the build is upstream-build
    if (build_status) {
      def description = build_info(response, build_status)
      sh "${WORKSPACE}/rhevm-qe-infra/scripts/production-monitoring/pygsheets-env.sh ${build_name} ${description} \
          ${build_status} ${env.BUILD_URL} is_upstream=true status_update=true"
      return
    }

    // Update GE Execution Sheet with pre-build configuration
    sh "${WORKSPACE}/rhevm-qe-infra/scripts/production-monitoring/pygsheets-env.sh ${build_name} ${currentBuild.description} \
        ${env.BUILD_URL} ${job_name} ${env.JENKINS_URL} ${env.JOB_BASE_NAME} status_update=false"

    def build_result = build job: job_name, parameters: job_parameters, propagate: false, wait: true

    // Update GE Execution Sheet with post-build configuration
    sh "${WORKSPACE}/rhevm-qe-infra/scripts/production-monitoring/pygsheets-env.sh ${build_name} ${currentBuild.description} \
        ${build_result.result} is_upstream=false status_update=true"

    return build_result
    }
}
