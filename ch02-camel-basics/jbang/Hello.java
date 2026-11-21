import org.apache.camel.builder.RouteBuilder;

public class Hello extends RouteBuilder {

    @Override
    public void configure() {
        from("timer:tick?period=2000")
            .setBody(simple("Hello from Camel at ${date:now:HH:mm:ss}"))
            .log("${body}");
    }
}
