# Toothpick

[![CI](https://github.com/shopstic/toothpick/actions/workflows/ci.yaml/badge.svg)](https://github.com/shopstic/toothpick/actions) [![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://github.com/shopstic/toothpick/blob/main/LICENSE) [![Docker](https://img.shields.io/docker/v/shopstic/toothpick-server?arch=amd64&color=%23ab47bc&label=Docker%20Image&sort=semver)](https://hub.docker.com/repository/docker/shopstic/toothpick-server/tags?page=1&ordering=last_updated&name=1.)

A Kubernetes-native, massively parallelized, distributed integration test runner for Scala. Currently, it supports [ScalaTest](https://www.scalatest.org/), with [ZIO Test](https://zio.dev/docs/usecases/usecases_testing) support coming soon.

## Installation

### Intellij Runner

Make sure your `JAVA_HOME` environment variable already points to an OpenJDK 11+ distribution, then run:

```bash
curl -L https://raw.githubusercontent.com/shopstic/toothpick/main/scripts/install.sh | bash
```

Which installs `latest` version to the default location of `~/.toothpick`.

Alternatively, to install a specific version to a specific location:

```bash
curl -L https://raw.githubusercontent.com/shopstic/toothpick/main/scripts/install.sh | bash -s VERSION LOCATION
```

Then add the Toothpick JDK facade to your project:

- Open the `Project Structure` dialog via `File > Project Structure` from the menu bar
- Under `Platform Settings > SDKs`, click on the `+` icon then click `Add JDK`
- Choose the path to the `jdk` directory inside the installed Toothpick directory, which defaults to `~/.toothpick/jdk`
- Rename the newly added JDK to `Toothpick`

### Server

The Helm chart is published as an [OCI registry package](https://helm.sh/docs/topics/registries/) at `ghcr.io/shopstic/chart-toothpick`

```bash
helm chart save /path/to/charts/toothpick/ ghcr.io/shopstic/chart-toothpick:"${COMMIT_HASH}"
```

## Configuration

Place a `.toothpick.conf` file at the root of your project directory to override any configuration. See [tp-runner-app.conf](./toothpick-runner/src/main/resources/dev/toothpick/app/tp-runner-app.conf) for reference.

## Usage

Run any `ScalaTest` as usual, then edit its Run configuration dialog. Scroll down to the bottom of the dialog and simply select `Toothpick` as the `JRE`. Click `Apply` then `Run`.
