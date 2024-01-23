package foo.zaaarf.routecompass;

import org.springframework.web.bind.annotation.*;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Annotation;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * The main processor class.
 */
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class RouteCompass extends AbstractProcessor {

	/**
	 * A {@link Map} tying each component class to the routes it contains.
	 */
	private final Map<String, List<Route>> foundRoutes = new HashMap<>();

	/**
	 * A {@link Set} containing all the supported annotation classes.
	 */
	private final Set<Class<? extends Annotation>> annotationClasses = new HashSet<>();

	/**
	 * Default constructor, it only initialises {@link #annotationClasses}.
	 */
	public RouteCompass() {
		this.annotationClasses.add(RequestMapping.class);
		this.annotationClasses.add(GetMapping.class);
		this.annotationClasses.add(PostMapping.class);
		this.annotationClasses.add(PutMapping.class);
		this.annotationClasses.add(DeleteMapping.class);
		this.annotationClasses.add(PatchMapping.class);
	}

	/**
	 * Processes Spring's annotations, NOT claiming them for itself.
	 * It builds a {@link Route} object for each route and adds it to {@link #foundRoutes},
	 * then proceeds to print it to a file.
	 * @param annotations the annotation types requested to be processed
	 * @param env environment for information about the current and prior round
	 * @return false, letting other processor process the annotations again
	 */
	@Override
	@SuppressWarnings("OptionalGetWithoutIsPresent")
	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
		for(TypeElement annotationType : annotations) {
			env.getElementsAnnotatedWith(annotationType)
				.stream()
				.filter(elem -> elem instanceof ExecutableElement)
				.map(elem -> (ExecutableElement) elem)
				.forEach(elem -> {
					String classFQN = elem.getEnclosingElement().asType().toString();
					List<Route> routesInClass = foundRoutes.computeIfAbsent(classFQN, k -> new ArrayList<>());
					routesInClass.add(new Route(
						this.getFullRoute(annotationType, elem),
						this.getRequestMethods(annotationType, elem),
						this.getConsumedType(annotationType, elem),
						this.getProducedType(annotationType, elem),
						this.isDeprecated(elem),
						this.getDTO(this.processingEnv.getTypeUtils().asElement(elem.getReturnType())),
						this.getDTO(elem.getParameters().stream()
							.filter(e -> e.getAnnotation(RequestBody.class) != null)
							.findFirst().get()),
						this.getQueryParams(elem.getParameters())
					));
				});
		}

		try {
			FileObject serviceProvider = this.processingEnv.getFiler().createResource(
				StandardLocation.SOURCE_OUTPUT, "", "routes"
			);

			PrintWriter out = new PrintWriter(serviceProvider.openWriter());
			for(String componentClass : this.foundRoutes.keySet()) {
				out.println(componentClass + ":");

				List<Route> routesInClass = this.foundRoutes.get(componentClass);
				for(Route r : routesInClass) {
					out.print("\t- ");
					if(r.deprecated) out.print("[DEPRECATED] ");
					out.print(r.method + " " + r.path);
					if(r.consumes != null) {
						out.print("(expects: " + Arrays.toString(r.consumes) + ")");
					}
					if(r.produces != null) out.print("(returns: " + Arrays.toString(r.produces) + ")");
					out.println();

					BiConsumer<String, Route.Param[]> printParam = (name, params) -> {
						if(name != null) out.println("\t\t" + name);
						for(Route.Param p : params) {
							out.print(name != null ? "\t\t\t" : "\t\t");
							out.print("- " + p.typeFQN + " " + p.name);
							if(p.defaultValue != null)
								out.print(" " + "(default: " + p.defaultValue + ")");
							out.println();
						}
					};

					printParam.accept(null, r.params);

					if(r.inputType != null)
						printParam.accept("input: " + r.inputType.FQN, r.inputType.fields);

					if(r.returnType != null)
						printParam.accept("output: " + r.returnType.FQN, r.returnType.fields);
				}
			}

			out.close();
		} catch(IOException e) {
			throw new RuntimeException(e);
		}

		return false; //don't claim them, let spring do its job
	}

	/**
	 * Extracts the route of an element.
	 * @param annotationType the {@link TypeElement} with the annotation we are processing
	 * @param element the {@link Element} currently being examined
	 * @return the full route of the endpoint
	 */
	private String getFullRoute(TypeElement annotationType, Element element) {
		try { //TODO support multiple routes
			String[] routes = this.getAnnotationFieldsValue(annotationType, element, "path", "value");
			return this.getParentOrFallback(element, routes[0], (a, e) -> {
				String parent = this.getFullRoute(a, e);
				StringBuilder sb = new StringBuilder(parent);
				if(!parent.endsWith("/")) sb.append("/");
				sb.append(routes[0]);
				return sb.toString();
			});
		} catch (ReflectiveOperationException ex) {
			throw new RuntimeException(ex); //if it fails something went very wrong
		}
	}

	/**
	 * Finds the request methods supported by the endpoint.
	 * @param annotationType the {@link TypeElement} with the annotation we are processing
	 * @param element the {@link Element} currently being examined
	 * @return the {@link RequestMethod}s supported by the endpoint
	 */
	private RequestMethod[] getRequestMethods(TypeElement annotationType, Element element) {
		RequestMethod[] methods = annotationType.getQualifiedName().contentEquals(RequestMapping.class.getName())
			? element.getAnnotation(RequestMapping.class).method()
			: annotationType.getAnnotation(RequestMapping.class).method();
		return methods.length == 0
			? this.getParentOrFallback(element, methods, this::getRequestMethods)
			: methods;
	}

	/**
	 * Finds the media type consumed by an endpoint.
	 * @param annotationType the {@link TypeElement} with the annotation we are processing
	 * @param element the {@link Element} currently being examined
	 * @return the media type consumed by the endpoint
	 */
	private String[] getConsumedType(TypeElement annotationType, Element element) {
		try {
			String[] res = this.getAnnotationFieldsValue(annotationType, element, "consumes");
			return res == null
				? this.getParentOrFallback(element, res, this::getConsumedType)
				: res;
		} catch(ReflectiveOperationException ex) {
			throw new RuntimeException(ex);
		}
	}

	/**
	 * Finds the media type consumed by an endpoint.
	 * @param annotationType the {@link TypeElement} with the annotation we are processing
	 * @param element the {@link Element} currently being examined
	 * @return the media type consumed by the endpoint
	 */
	private String[] getProducedType(TypeElement annotationType, Element element) {
		try {
			String[] res = this.getAnnotationFieldsValue(annotationType, element, "produces");
			return res == null
				? this.getParentOrFallback(element, res, this::getProducedType)
				: res;
		} catch(ReflectiveOperationException ex) {
			throw new RuntimeException(ex);
		}
	}

	/**
	 * Checks whether the endpoint or its parent are deprecated
	 * @param element the {@link Element} currently being examined
	 * @return whether the given endpoint is deprecated
	 */
	private boolean isDeprecated(Element element) {
		return element.getAnnotation(Deprecated.class) != null
			|| element.getEnclosingElement().getAnnotation(Deprecated.class) != null;
	}

	/**
	 * Gets the parameters accepted by a request.
	 * @param params the {@link VariableElement}s representing the parameters of a request
	 * @return an array of {@link Route.Param} representing the parameters of the request.
	 */
	private Route.Param[] getQueryParams(List<? extends VariableElement> params) {
		return params.stream()
			.map(p -> {
				RequestParam ann = p.getAnnotation(RequestParam.class);
				if(ann == null) return null;

				String name = ann.name(); //first try annotation.name()
				name = name.isEmpty()
					? ann.value() //then annotation.value()
					: name;
				name = name.isEmpty()
					? p.getSimpleName().toString() //fall back on parameter name
					: name;

				String defaultValue = ann.defaultValue();
				if(defaultValue.equals(ValueConstants.DEFAULT_NONE))
					defaultValue = null;

				return new Route.Param(name, defaultValue, p.asType().toString());
			}).filter(Objects::nonNull).toArray(Route.Param[]::new);
	}

	/**
	 * Gets a representation of a DTO type.
	 * @param type the {@link TypeElement} to examine
	 * @return a {@link Route.DTO} representing the given type
	 */
	private Route.DTO getDTO(Element type) {
		if(!(type instanceof TypeElement)) //doubles as null check
			return null;

		List<VariableElement> fieldElements = new ArrayList<>();
		TypeElement typeElement = (TypeElement) type;
		do {
			fieldElements.addAll(typeElement
				.getEnclosedElements()
				.stream().filter(e -> e instanceof VariableElement)
				.map(e -> (VariableElement) e)
				.collect(Collectors.toList()));
			TypeMirror superclass = typeElement.getSuperclass();
			if(superclass.getKind() == TypeKind.DECLARED)
				typeElement = (TypeElement) this.processingEnv.getTypeUtils().asElement(superclass);
			else typeElement = null;
		} while(typeElement != null);

		return new Route.DTO(type.asType().toString(), fieldElements.stream() //TODO @JsonIgnore
			.map(e -> new Route.Param(e.asType().toString(), e.getSimpleName().toString(), null))
			.toArray(Route.Param[]::new));
	}

	/**
	 * An annotation value.
	 * @param annotationType the {@link TypeElement} with the annotation we are processing
	 * @param element the {@link Element} currently being examined
	 * @param fieldNames the field name(s) to look for; they are tried in order, and the first found is returned
	 * @return the field value, cast to the expected type
	 * @param <T> the expected type of the field
	 * @throws ReflectiveOperationException when given non-existing or inaccessible field names (hopefully never)
	 */
	@SuppressWarnings({"OptionalGetWithoutIsPresent", "unchecked"})
	private <T> T getAnnotationFieldsValue(TypeElement annotationType, Element element, String ... fieldNames)
		throws ReflectiveOperationException {

		Class<? extends Annotation> annClass = this.annotationClasses.stream()
			.filter(c -> annotationType.getQualifiedName().contentEquals(c.getName()))
			.findFirst()
			.get(); //should never fail

		T result = null;
		for(String fieldName : fieldNames) {
			result = (T) annClass.getMethod(fieldName).invoke(element.getAnnotation(annClass));
			if(result != null) return result;
		}

		return result;
	}

	/**
	 * Finds whether the parent of the given element has any supported annotation, then applies the given
	 * function to both parent and found annotation.
	 * @param element the {@link Element} currently being examined
	 * @param fallback the value to return if the parent didn't have any supported annotations
	 * @param fun the {@link BiFunction} to apply
	 * @return the output or the function, or the fallback value if the parent didn't have any supported annotation
	 * @param <T> the type of the expected result
	 */
	private <T> T getParentOrFallback(Element element, T fallback, BiFunction<TypeElement, Element, T> fun) {
		List<Class<? extends Annotation>> found = this.annotationClasses.stream()
			.filter(annClass -> element.getEnclosingElement().getAnnotation(annClass) != null)
			.collect(Collectors.toList());

		if(found.isEmpty()) return fallback;

		if(found.size() > 1) this.processingEnv.getMessager().printMessage(
			Diagnostic.Kind.WARNING,
			"Found multiple mapping annotations on "
				+ element.getSimpleName().toString()
				+ ", only one of them will be considered!"
		);

		return fun.apply(
			this.processingEnv.getElementUtils()
				.getTypeElement(found.get(0).getName()),
			element
		);
	}

	/**
	 * @return the types of annotations supported by this processor
	 */
	@Override
	public Set<String> getSupportedAnnotationTypes() {
		return annotationClasses.stream().map(Class::getCanonicalName).collect(Collectors.toSet());
	}
}
