pipeline {
  agent any
  stages {
    stage('Native build') { steps { sh 'cmake -S . -B build'; sh 'cmake --build build --parallel' } }
    stage('Control plane') { steps { dir('control-plane') { sh 'mvn -B test' } } }
  }
  post { always { junit allowEmptyResults: true, testResults: '**/target/surefire-reports/*.xml' } }
}
