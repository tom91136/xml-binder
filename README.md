# xml-binder

[![CI](https://github.com/tom91136/xml-binder/actions/workflows/ci.yml/badge.svg)](https://github.com/tom91136/xml-binder/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/net.kurobako/xml-binder_3)](https://central.sonatype.com/artifact/net.kurobako/xml-binder_3)
[![Javadoc](https://javadoc.io/badge2/net.kurobako/xml-binder_3/javadoc.svg)](https://javadoc.io/doc/net.kurobako/xml-binder_3)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

A simple Scala 3 library for binding XML to algebraic data types via compile‑time derivation.
Unlike scalaxb, this library does not handle namespaces or generate code from a schema.
It simply treats XML as a tree, similar to how uPickle supports case class derivation.

## Features

- **Derives** `XmlBinding[A]` for case classes and enums using `Mirror`‑based macros  
- **Field annotations**:
  - `@attrName("xml-name")` to bind an attribute to a field
  - `@tagName` / `@text` to bind an element’s tag or text content
  - `@children` to collect child elements
  - `@extras` to collect unmapped attributes
- **Sum‑type support** with dispatch via `@matchTag(...)` / `@matchAttr(...)`
- Configurable global attribute name mapping support, defaulting to a kebab-case to camelCase
- Custom attribute type mapping via `XmlMappable[A]`

## Installation

Add to your SBT project:
```scala sc:nocompile ignore
libraryDependencies += "net.kurobako" %% "xml-binder" % "0.1.0"
```

## Usage

```scala
import net.kurobako.xmlbinder.*
import net.kurobako.xmlbinder.scalaxml.given // or: import net.kurobako.xmlbinder.dom.given

case class Person( // Simple product type
  @attrName("id") id: Int,
  @text name: String,
  missing: Option[Int],
  @extras attrs: Map[String,String]
) derives XmlBinding

val xml = "<person id=\"42\" foo=\"bar\">Alice</person>"
val p   = XmlBinding.string[scala.xml.Elem, Person](xml)
// Person(42, "Alice", None, Map("foo" -> "bar"))

enum Shape derives XmlBinding { // Sum type
  @matchTag("circle") case Circle(r: Int)
  @matchTag("rect") case Rectangle(w: Int, h: Int)
}

XmlBinding.string[scala.xml.Elem, Shape]("<circle r=\"5\"/>")  // Shape.Circle(5)

case class MyType(`foo+bar`: String) derives XmlBinding

XmlBinding.string[scala.xml.Elem, MyType]("<node FOO_BAR=\"42\" />", _.toLowerCase.replace('_', '+'))
// MyType("42")

```

## Release process

1. One-time setup: configure scala-cli with Sonatype credentials and GPG key. Check if already configured first:
   ```
   scala-cli --power config publish.credentials
   scala-cli --power config pgp.secret-key
   scala-cli --power config pgp.public-key
   ```
   If any are missing, set them up:
   ```
   scala-cli --power config publish.credentials central.sonatype.com value:<token-username> value:<token-password>
   scala-cli --power config pgp.secret-key "value:$(gpg --armor --export-secret-keys <KEY_ID>)"
   scala-cli --power config pgp.secret-key-password "value:<gpg-passphrase>"
   scala-cli --power config pgp.public-key "value:$(gpg --armor --export <KEY_ID>)"
   ```
   Generate the token at https://central.sonatype.com.
2. Make sure all changes are committed and tests pass:
   ```
   scala-cli test . 
   git push
   ```
3. Tag the release version:
   ```
   git tag v0.1.0
   ```
4. Dry run: verify everything builds, signs, and resolves without uploading:
   ```
   scala-cli --power publish . --cross --dummy
   ```
5. Publish:
   ```
   scala-cli --power publish . --cross
   ```
6. Push the tag:
   ```
   git push
   git push --tags
   ```

## Licence

    Copyright 2026 WeiChen Lin

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
