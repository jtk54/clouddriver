Notes on using gradlew to build and publish debs
================================================

Nebula is used extensively by [spinnaker-dependencies](https://github.com/spinnaker/spinnaker-dependencies).
The nebula component we care about for Spinnaker product releases is
[nebula-release](https://github.com/nebula-plugins/nebula-release-plugin#releasing-using-last-tag).


The way this plugin is currently used for releases is the following:

* _master_ is tagged with some version tag via github's **Releases** tab.

* TravisCI runs:
``` bash
./gradlew -Prelease.travisci=true -Prelease.useLastTag=true -PbintrayUser="${bintrayUser}" -PbintrayKey="${bintrayKey}" final --stacktrace
```
where `final` is a the final release gradle task.


To build and publish a package from source (when we're ready) during the
Spinnaker product release process:

* Pull the latest source from each component's _master_. This can be before or
after validation.

* Annotate the source tree with `sX.Y.Z` tags locally. Create the stable release
branch locally as well.

* Publish the components with `-Prelease.useLastTag=true` **from the local
branch** and with whatever `bintray*` properties we want. Here's an [example](https://github.com/spinnaker/spinnaker-gradle-project/blob/master/src/main/groovy/com/netflix/spinnaker/gradle/project/SpinnakerProjectConventionsPlugin.groovy#L48)
of what the bintray properties are.

* Do _all the validation_, etc.
