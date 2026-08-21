fn main() {
    let arguments: Vec<String> = std::env::args().skip(1).collect();
    if let Err(error) = mojito_cli::run(&arguments) {
        eprintln!("Error: {error}");
        std::process::exit(1);
    }
}
