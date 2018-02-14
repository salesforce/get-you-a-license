# Get You A License

A little web app that makes it easy to add licenses to projects on GitHub. Check it out:

![Get You a License](docs/get-you-a-license.gif)

Run on Heroku:

- Deploy: [![Deploy](https://www.herokucdn.com/deploy/button.svg)](https://heroku.com/deploy)

Run Locally:

- Run Web App:

    ./sbt ~run

- Try it out: [http://localhost:9000](http://localhost:9000)

Test Locally:

- Create a GitHub org & repo for testing
- Create a GitHub auth token
- Set env vars:

        export GITHUB_OAUTH_CLIENT_ID=BLAH
        export GITHUB_OAUTH_CLIENT_SECRET=BLAH
        export GITHUB_TEST_TOKEN=BLAH
        export GITHUB_TEST_ORG=BLAH
        export GITHUB_TEST_REPO=BLAH
        export GITHUB_TEST_USER=BLAH

- Run tests:

    ./sbt test

