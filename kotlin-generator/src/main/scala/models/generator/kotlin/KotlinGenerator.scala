package models.generator.kotlin

import java.io.StringWriter

import com.fasterxml.jackson.annotation._
import com.fasterxml.jackson.core.{JsonGenerator, JsonParser, Version}
import com.fasterxml.jackson.databind._
import com.fasterxml.jackson.databind.module.SimpleModule
import com.squareup.kotlinpoet._
import io.apibuilder.generator.v0.models.{File, InvocationForm}
import io.apibuilder.spec.v0.models._
import io.reactivex.Single
import lib.generator.CodeGenerator
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormatter, ISODateTimeFormat}

import scala.collection.JavaConverters._

class KotlinGenerator
  extends CodeGenerator
    with KotlinUtil {

  private implicit def classToClassName(clazz: java.lang.Class[_]): ClassName = new ClassName(clazz.getPackage.getName, clazz.getSimpleName)

  private val kdocClassMessage = s"This code was generated by [${classOf[KotlinGenerator].getName}]\n"

  override def invoke(form: InvocationForm): Either[Seq[String], Seq[File]] = {
    Right(new GeneratorHelper(form.service).generateSourceFiles(form.service))
  }

  class GeneratorHelper(service: Service) {

    private val nameSpace = makeNameSpace(service.namespace)
    private val modelsNameSpace = nameSpace + ".models"
    private val modelsDirectoryPath = createDirectoryPath(modelsNameSpace)

    private val sharedJacksonSpace = modelsNameSpace
    private val sharedObjectMapperClassName = "JacksonObjectMapperFactory"

    def createDirectoryPath(namespace: String) = namespace.replace('.', '/')

    def generateEnum(enum: io.apibuilder.spec.v0.models.Enum): File = {
      val className = toClassName(enum.name)

      val builder = TypeSpec.enumBuilder(className)
        .addModifiers(KModifier.PUBLIC)
        .addKdoc(kdocClassMessage)

      enum.description.map(builder.addKdoc(_))

      val allEnumValues = enum.values ++ Seq(io.apibuilder.spec.v0.models.EnumValue(undefinedEnumName, Some(undefinedEnumName)))

      allEnumValues.foreach(value => {
        val annotation = AnnotationSpec.builder(classOf[JsonProperty]).addMember("\"" + value.name + "\"")
        builder.addEnumConstant(toEnumName(value.name), TypeSpec.anonymousClassBuilder("\"" + value.name + "\"").addAnnotation(annotation.build()).build())
      })

      val nameField = "jsonProperty"
      val nameFieldType = ClassName.bestGuess("kotlin.String")
      builder.addProperty(PropertySpec.builder(nameField, nameFieldType, KModifier.PRIVATE, KModifier.FINAL).build())

      val constructorWithParams = FunSpec.constructorBuilder()
      val constructorParameter = ParameterSpec.builder(nameField, nameFieldType)
      constructorWithParams.addParameter(constructorParameter.build)
      constructorWithParams.addStatement(s"this.$nameField = $nameField")
      builder.primaryConstructor(constructorWithParams.build())

      val toStringMethod = FunSpec.builder("toString").addModifiers(KModifier.OVERRIDE, KModifier.PUBLIC).returns(nameFieldType)
      toStringMethod.addStatement(s"return $nameField")
      builder.addFunction(toStringMethod.build)

      makeFile(className, builder)
    }

    def getRetrofitReturnTypeWrapperClass(): ClassName = classToClassName(classOf[Single[Void]])

    def generateUnionType(union: Union): File = {
      val className = toClassName(union.name)

      val builder = TypeSpec.interfaceBuilder(className)
        .addModifiers(KModifier.PUBLIC)
        .addKdoc(kdocClassMessage)

      val jsonIgnorePropertiesAnnotation = AnnotationSpec.builder(classOf[JsonIgnoreProperties])
        .addMember("ignoreUnknown=true")
      builder.addAnnotation(jsonIgnorePropertiesAnnotation.build)

      val jsonAnnotationBuilder = AnnotationSpec.builder(classOf[JsonTypeInfo])
      jsonAnnotationBuilder.addMember("use", "com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME")
      if (union.discriminator.isDefined) {
        jsonAnnotationBuilder.addMember("include", "com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY")
        jsonAnnotationBuilder.addMember("property", "\"" + union.discriminator.get + "\"")
      } else {
        jsonAnnotationBuilder.addMember("include", "com.fasterxml.jackson.annotation.JsonTypeInfo.As.WRAPPER_OBJECT")
      }

      builder.addAnnotation(jsonAnnotationBuilder.build)

      val jsonSubTypesAnnotationBuilder = AnnotationSpec.builder(classOf[JsonSubTypes])
      union.types.foreach(u => {
        jsonSubTypesAnnotationBuilder
          .addMember("value", "$L", AnnotationSpec.builder(classOf[JsonSubTypes.Type])
            .addMember("value", "$L", dataTypeFromField(u.`type`, modelsNameSpace) + ".class")
            .addMember("name", "$S", u.`type`)
            .build())
      })

      builder.addAnnotation(jsonSubTypesAnnotationBuilder.build())

      union.description.map(builder.addKdoc(_))
      makeFile(className, builder)
    }

    def generateModel(model: Model, relatedUnions: Seq[Union]): File = {
      val className = toClassName(model.name)

      val builder = TypeSpec.classBuilder(className)
        .addModifiers(KModifier.PUBLIC, KModifier.DATA)
        .addKdoc(kdocClassMessage)

      val jsonIgnorePropertiesAnnotation = AnnotationSpec.builder(classOf[JsonIgnoreProperties]).addMember("ignoreUnknown=true")
      builder.addAnnotation(jsonIgnorePropertiesAnnotation.build)

      builder.addSuperinterface(classOf[java.io.Serializable])


      model.description.map(builder.addKdoc(_))

      val constructorWithParams = FunSpec.constructorBuilder()

      val unionClassTypeNames = relatedUnions.map { u => new ClassName(modelsNameSpace, toClassName(u.name)) }
      builder.addSuperinterfaces(unionClassTypeNames.asJava)

      relatedUnions.headOption.map { _ =>
        val jsonAnnotationBuilder = AnnotationSpec.builder(classOf[JsonTypeInfo])
        jsonAnnotationBuilder.addMember("use", "com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NONE")
        builder.addAnnotation(jsonAnnotationBuilder.build())
      }

      val propSpecs = new java.util.ArrayList[PropertySpec]
      model.fields.foreach(field => {

        val fieldSnakeCaseName = field.name
        val arrayParameter = isParameterArray(field.`type`)
        val fieldCamelCaseName = toParamName(fieldSnakeCaseName, true)

        val kotlinDataType = dataTypeFromField(field.`type`, modelsNameSpace)

        val annotation = AnnotationSpec.builder(classOf[JsonProperty]).addMember("\"" + field.name + "\"")
        val constructorParameter = ParameterSpec.builder(fieldCamelCaseName, if (field.required) kotlinDataType.asNonNullable() else kotlinDataType.asNullable())
        constructorWithParams.addParameter(constructorParameter.build)
        propSpecs.add(
          PropertySpec.builder(fieldCamelCaseName, if (field.required) kotlinDataType.asNonNullable() else kotlinDataType.asNullable())
            .initializer(fieldCamelCaseName)
            .addAnnotation(annotation.build())
            .build()
        )
      })

      builder.primaryConstructor(constructorWithParams.build).addProperties(propSpecs)

      val toJsonString = FunSpec.builder("toJsonString").addModifiers(KModifier.PUBLIC).returns(ClassName.bestGuess("kotlin.String")) //.addException(classOf[JsonProcessingException])
      toJsonString.addStatement(s"return ${sharedJacksonSpace}.${sharedObjectMapperClassName}.create().writeValueAsString(this)")
      builder.addFunction(toJsonString.build)

      val companionBuilder = TypeSpec.companionObjectBuilder()
      val fromBuilder = FunSpec.builder("parseJson").addModifiers(KModifier.PUBLIC).addParameter("json", ClassName.bestGuess("kotlin.String"))
      val modelType = ClassName.bestGuess(modelsNameSpace + "." + className)
      fromBuilder.returns(modelType)
      fromBuilder.addStatement(s"return ${sharedJacksonSpace}.${sharedObjectMapperClassName}.create().readValue( json, ${modelType.simpleName() + "::class.java"})")
      companionBuilder.addFunction(fromBuilder.build)
      builder.companionObject(companionBuilder.build())


      makeFile(className, builder)
    }

    def generateResource(resource: Resource): File = {
      val className = toClassName(resource.plural) + "Client"

      val builder = TypeSpec.interfaceBuilder(className)
        .addModifiers(KModifier.PUBLIC)
        .addKdoc(kdocClassMessage)


      resource.operations.foreach { operation =>

        val maybeAnnotationClass = operation.method match {
          case Method.Get => Some(classOf[retrofit2.http.GET])
          case Method.Post => Some(classOf[retrofit2.http.POST])
          case Method.Put => Some(classOf[retrofit2.http.PUT])
          case Method.Patch => Some(classOf[retrofit2.http.PATCH])
          case Method.Delete => Some(classOf[retrofit2.http.DELETE])
          case Method.Head => Some(classOf[retrofit2.http.HEAD])
          case Method.Connect => None
          case Method.Options => None
          case Method.Trace => None
          case _ => None
        }

        import RetrofitUtil._

        val retrofitPath = toRetrofitPath(operation.path)

        maybeAnnotationClass.map(annotationClass => {

          val methodAnnotation = AnnotationSpec.builder(annotationClass).addMember("value=\"" + retrofitPath + "\"").build()
          val methodName =
            if (operation.path == "/")
              toMethodName(operation.method.toString.toLowerCase)
            else
              toMethodName(operation.method.toString.toLowerCase + "_" + operation.path.replaceAll("/", "_"))

          val method = FunSpec.builder(methodName).addModifiers(KModifier.PUBLIC).addModifiers(KModifier.ABSTRACT)

          operation.description.map(description => {
            method.addKdoc(description)
          })

          operation.deprecation.map(deprecation => {
            val deprecationAnnotation = AnnotationSpec.builder(classOf[Deprecated]).build
            method.addAnnotation(deprecationAnnotation)
            deprecation.description.map(description => {
              method.addKdoc("\n@deprecated: " + description)
            })
          })

          method.addAnnotation(methodAnnotation)

          operation.body.map(body => {
            val bodyType = dataTypeFromField(body.`type`, modelsNameSpace)

            val parameter = ParameterSpec.builder(toParamName(body.`type`, true), bodyType)
            val annotation = AnnotationSpec.builder(classOf[retrofit2.http.Body]).build
            parameter.addAnnotation(annotation)
            method.addParameter(parameter.build())
          })

          operation.parameters.foreach(parameter => {

            val maybeAnnotationClass = parameter.location match {
              case ParameterLocation.Path => Some(classOf[retrofit2.http.Path])
              case ParameterLocation.Query => Some(classOf[retrofit2.http.Query])
              case ParameterLocation.Form => Some(classOf[retrofit2.http.Query])
              case ParameterLocation.Header => Some(classOf[retrofit2.http.Header])
              case _ => None
            }

            maybeAnnotationClass.map(annotationClass => {
              val parameterType: TypeName = dataTypeFromField(parameter.`type`, modelsNameSpace)
              val param = ParameterSpec.builder(toParamName(parameter.name, true), if (parameter.required) parameterType.asNonNullable() else parameterType.asNullable())
              val annotation = AnnotationSpec.builder(annotationClass).addMember("value=\"" + parameter.name + "\"").build
              param.addAnnotation(annotation)
              method.addParameter(param.build)
            })
          })

          /*
          this is where it gets a little ugly with apidoc/retrofit mapping.
          apidoc says "map the response code to response type", for example:

          "responses": {
            "201": { "type": "checkout_session"},
            "400": {"type": "error_response"},
            "401": {"type": "error_response"},
            "422": {"type": "error_response"}
          }

          retrofit, on the other hand, treats codes 200-299 as success and others as failure

          I think in most cases we can find a single 200-299 result and map it as success, and for other
          codes clients can do special handling based on response codes (without understanding the response object)

         */

          val maybeSuccessfulResponse = operation.responses.find(response => {
            response.code.isInstanceOf[ResponseCodeInt] &&
              response.code.asInstanceOf[ResponseCodeInt].value >= 200 &&
              response.code.asInstanceOf[ResponseCodeInt].value < 299
          })

          maybeSuccessfulResponse.map(successfulResponse => {
            val returnType = dataTypeFromField(successfulResponse.`type`, modelsNameSpace)
            val retrofitWrappedClassname = getRetrofitReturnTypeWrapperClass()
            method.returns(ParameterizedTypeName.get(retrofitWrappedClassname, returnType))
          })
          builder.addFunction(method.build)
        })
      }

      makeFile(className, builder)
    }

    def generateJacksonObjectMapper(): File = {

      val formatterProperty = PropertySpec.builder("formatter", classOf[DateTimeFormatter])
        .initializer("%T.dateTimeParser()", classOf[ISODateTimeFormat])
        .build()

      val moduleProperty = PropertySpec.builder("module", classOf[SimpleModule])
        .initializer("%T(%T(1, 0, 0, null, null, null))", classOf[SimpleModule], classOf[Version])
        .build()

      val deserializeFunction = FunSpec.builder("deserialize")
        .addParameter("jsonParser", classOf[JsonParser])
        .addParameter("deserializationContext", classOf[DeserializationContext])
        .returns(classOf[DateTime])
        .addModifiers(KModifier.OVERRIDE)
        .addStatement("val value = jsonParser.valueAsString")
        .addStatement("return formatter.parseDateTime(value)", formatterProperty)
        .build()
      val deserializerType = TypeSpec.objectBuilder("DateTimeDeserializer")
        .superclass(ParameterizedTypeName.get(classOf[JsonDeserializer[DateTime]], classOf[DateTime]))
        .addFunction(deserializeFunction)
        .build()


      val serializeFunction = FunSpec.builder("serialize")
        .addParameter("value", classOf[DateTime])
        .addParameter("jsonGenerator", classOf[JsonGenerator])
        .addParameter("serializerProvider", classOf[SerializerProvider])
        .addModifiers(KModifier.OVERRIDE)
        .addStatement("jsonGenerator.writeString(value.toString(formatter))")
        .build()
      val serializerType = TypeSpec.objectBuilder("DateTimeSerializer")
        .superclass(ParameterizedTypeName.get(classOf[JsonSerializer[DateTime]], classOf[DateTime]))
        .addFunction(serializeFunction)
        .build()


      val className = sharedObjectMapperClassName
      val deserializationFeatureClassName = classOf[DeserializationFeature].getName
      val createCodeBlock = CodeBlock.builder()
        .addStatement("val mapper = com.fasterxml.jackson.databind.ObjectMapper()")
        .addStatement("mapper.registerModule(com.fasterxml.jackson.module.kotlin.KotlinModule())")
        .addStatement("mapper.registerModule(com.fasterxml.jackson.datatype.joda.JodaModule())")
        .addStatement(s"mapper.configure(${deserializationFeatureClassName}.FAIL_ON_UNKNOWN_PROPERTIES, false)")
        .addStatement(s"mapper.configure(${deserializationFeatureClassName}.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true)")
        .addStatement("module.addDeserializer(DateTime::class.java, DateTimeDeserializer)")
        .addStatement("module.addSerializer(DateTime::class.java, DateTimeSerializer)")
        .addStatement("mapper.registerModule(module)")
        .addStatement("return mapper")
        .build()
      val createFunSpec = FunSpec.builder("create")
        .addCode(createCodeBlock)
        .addModifiers(KModifier.PUBLIC)
        .returns(classOf[com.fasterxml.jackson.databind.ObjectMapper])
        .build()
      val builder = TypeSpec.objectBuilder(className)
        .addModifiers(KModifier.PUBLIC)
        .addKdoc(kdocClassMessage)
        .addProperty(formatterProperty)
        .addProperty(moduleProperty)
        .addType(deserializerType)
        .addType(serializerType)
        .addFunction(createFunSpec)
      makeFile(className, builder)
    }

    def generateEnums(enums: Seq[Enum]): Seq[File] = {
      enums.map(generateEnum(_))
    }

    def generateSourceFiles(service: Service): Seq[File] = {
      val generatedEnums = generateEnums(service.enums)

      val generatedUnionTypes = service.unions.map(generateUnionType(_))

      val generatedModels = service.models.map { model =>
        val relatedUnions = service.unions.filter(_.types.exists(_.`type` == model.name))
        generateModel(model, relatedUnions)
      }

      val generatedObjectMapper = Seq(generateJacksonObjectMapper())

      val generatedResources = service.resources.map(generateResource(_))

      generatedEnums ++
        generatedUnionTypes ++
        generatedModels ++
        generatedObjectMapper ++
        generatedResources
    }

    def makeFile(name: String, typeSpecBuilder: TypeSpec.Builder): File = {
      val typeSpec = typeSpecBuilder.build
      val kFile = FileSpec.get(modelsNameSpace, typeSpec)
      val sw = new StringWriter(1024)
      try {
        kFile.writeTo(sw)
        File(s"${name}.kt", Some(modelsDirectoryPath), sw.toString)
      } finally {
        sw.close()
      }
    }

  }

}

object KotlinRxClasses extends KotlinGenerator
