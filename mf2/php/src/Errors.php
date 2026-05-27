<?php

declare(strict_types=1);

namespace MF2;

final class MF2Error extends \Exception
{
    public string $mf2Code;

    public function __construct(string $code, string $message)
    {
        parent::__construct($message);
        $this->mf2Code = $code;
    }

    public static function missingArgument(string $name): self
    {
        return new self('missing-argument', "Missing argument \${$name}.");
    }

    public static function badOperand(string $message): self
    {
        return new self('bad-operand', $message);
    }

    public static function badSelector(string $message): self
    {
        return new self('bad-selector', $message);
    }

    public static function badOption(string $message): self
    {
        return new self('bad-option', $message);
    }
}

function error_code(\Throwable $error): string
{
    return $error instanceof MF2Error ? $error->mf2Code : get_class($error);
}
